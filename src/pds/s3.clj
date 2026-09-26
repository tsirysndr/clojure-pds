(ns pds.s3
  (:require [clojure.string :as str]
            [pds.blobs :as blobs]
            [pds.protocol.codec :as codec])
  (:import [java.net URI]
           [java.time Duration]
           [java.util UUID]
           [software.amazon.awssdk.auth.credentials AwsBasicCredentials AwsSessionCredentials AwsCredentialsProvider
            DefaultCredentialsProvider StaticCredentialsProvider]
           [software.amazon.awssdk.core.client.config ClientOverrideConfiguration]
           [software.amazon.awssdk.core.checksums RequestChecksumCalculation ResponseChecksumValidation]
           [software.amazon.awssdk.core.sync RequestBody]
           [software.amazon.awssdk.http.urlconnection UrlConnectionHttpClient]
           [software.amazon.awssdk.regions Region]
           [software.amazon.awssdk.services.s3 S3Client S3ClientBuilder S3Configuration]
           [software.amazon.awssdk.services.s3.model GetObjectRequest PutObjectRequest DeleteObjectRequest]))

(defn- config-error! [message] (throw (ex-info message {})))

(defn settings [env]
  (let [backend (get env "PDS_BLOB_BACKEND" "postgres")]
    (when-not (#{"postgres" "s3"} backend)
      (config-error! "PDS_BLOB_BACKEND must be postgres or s3"))
    (when (= "s3" backend)
      (let [bucket (get env "PDS_S3_BUCKET")
            region (get env "PDS_S3_REGION" "us-east-1")
            endpoint (get env "PDS_S3_ENDPOINT")
            uri (when endpoint (try (URI. endpoint) (catch Exception _ nil)))
            access (get env "PDS_S3_ACCESS_KEY_ID") secret (get env "PDS_S3_SECRET_ACCESS_KEY")
            session (get env "PDS_S3_SESSION_TOKEN")
            path-style (get env "PDS_S3_FORCE_PATH_STYLE" "false")
            prefix (get env "PDS_S3_PREFIX" "blobs")]
        (when-not (and (string? bucket) (<= 3 (count bucket) 63)
                       (re-matches #"[a-z0-9][a-z0-9.-]*[a-z0-9]" bucket)
                       (not (re-find #"\.\.|\.\-|-\.|^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$" bucket)))
          (config-error! "PDS_S3_BUCKET must be a valid bucket name"))
        (when-not (and (string? region) (re-matches #"[a-z0-9-]+" region))
          (config-error! "PDS_S3_REGION must be a nonempty region (use auto for Cloudflare R2)"))
        (when (and endpoint
                   (not (and uri (.getHost uri) (nil? (.getUserInfo uri))
                             (nil? (.getQuery uri)) (nil? (.getFragment uri)) (#{"" "/"} (.getPath uri))
                             (or (= "https" (.getScheme uri))
                                 (and (= "http" (.getScheme uri)) (#{"localhost" "127.0.0.1" "[::1]"} (.getHost uri)))))))
          (config-error! "PDS_S3_ENDPOINT must be an HTTPS origin (loopback HTTP allowed)"))
        (when (and (or access secret session) (or (str/blank? access) (str/blank? secret) (and session (str/blank? session))))
          (config-error! "Provide both PDS_S3_ACCESS_KEY_ID and PDS_S3_SECRET_ACCESS_KEY, or neither"))
        (when-not (#{"true" "false"} path-style)
          (config-error! "PDS_S3_FORCE_PATH_STYLE must be true or false"))
        (when-not (and (string? prefix) (<= (count prefix) 512)
                       (or (= "" prefix) (re-matches #"[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*" prefix)))
          (config-error! "PDS_S3_PREFIX must contain safe path segments without leading or trailing slashes"))
        {:bucket bucket :region region :endpoint uri :access-key access :secret-key secret
         :session-token session :path-style (= "true" path-style) :prefix prefix}))))

(defn object-key [prefix did cid]
  ;; Hash the DID so every account has a separate namespace without exposing it
  ;; in object names. CIDs and prefixes cannot introduce traversal segments.
  (str (when (seq prefix) (str prefix "/")) (codec/base32 (codec/sha256 (codec/utf8 did))) "/" cid))

(defrecord S3Store [^S3Client client credentials bucket prefix]
  blobs/ObjectStore
  (put-object! [_ did cid content mime-type]
    ;; A DELETE whose response timed out may still finish remotely. Never reuse
    ;; its target for a later upload, even when the bytes/CID are identical.
    (let [key (str (object-key prefix did cid) "/" (UUID/randomUUID))
          request (-> (PutObjectRequest/builder) (.bucket bucket) (.key key)
                      (.contentType mime-type) .build)]
      (.putObject client ^PutObjectRequest request (RequestBody/fromBytes content))
      {:object-key key :object-bucket bucket}))
  (get-object! [_ bucket key size]
    (when-not (<= 0 size blobs/max-size) (blobs/unavailable!))
    (let [request (-> (GetObjectRequest/builder) (.bucket bucket) (.key key) .build)]
      (with-open [stream (.getObject client ^GetObjectRequest request)]
        ;; Close aborts unread excess content. Never buffer an arbitrary remote
        ;; response, even if its Content-Length header is absent or incorrect.
        (let [data (.readNBytes stream (int (inc size)))]
          (when (> (alength data) size) (.abort stream))
          data))))
  blobs/ObjectDeletion
  (delete-object! [_ bucket key]
    (.deleteObject client ^DeleteObjectRequest (-> (DeleteObjectRequest/builder) (.bucket bucket) (.key key) .build)))
  java.io.Closeable
  (close [_]
    (try (.close client)
         (finally (when (instance? java.lang.AutoCloseable credentials)
                    (.close ^java.lang.AutoCloseable credentials))))))

(defn open-store
  "Create one reusable S3 client, owned by the server. Nil selects PostgreSQL.
  Explicit keys are optional; otherwise use the AWS default credential chain."
  [config]
  (when config
    (let [{:keys [bucket region endpoint access-key secret-key session-token path-style prefix]} config
          ^AwsCredentialsProvider credentials (if access-key
                        (StaticCredentialsProvider/create
                         (if session-token (AwsSessionCredentials/create access-key secret-key session-token)
                             (AwsBasicCredentials/create access-key secret-key)))
                        (.build (DefaultCredentialsProvider/builder)))
          ^S3Configuration service-config (-> (S3Configuration/builder) (.chunkedEncodingEnabled false) .build)
          ^ClientOverrideConfiguration overrides (-> (ClientOverrideConfiguration/builder)
                                                     (.apiCallTimeout (Duration/ofSeconds 30))
                                                     (.apiCallAttemptTimeout (Duration/ofSeconds 10)) .build)
          ^S3ClientBuilder builder (S3Client/builder)]
      (doto builder
                      (.region (Region/of region)) (.credentialsProvider credentials)
                      (.forcePathStyle (boolean path-style))
                      (.serviceConfiguration service-config)
                      (.requestChecksumCalculation RequestChecksumCalculation/WHEN_REQUIRED)
                      (.responseChecksumValidation ResponseChecksumValidation/WHEN_REQUIRED)
                      (.httpClientBuilder (-> (UrlConnectionHttpClient/builder)
                                              (.connectionTimeout (Duration/ofSeconds 3))
                                              (.socketTimeout (Duration/ofSeconds 10))))
                      (.overrideConfiguration overrides))
      (when endpoint (.endpointOverride builder endpoint))
      (try
        (->S3Store (.build builder) credentials bucket prefix)
        (catch Throwable t
          (when (instance? java.lang.AutoCloseable credentials) (.close ^java.lang.AutoCloseable credentials))
          (throw t))))))
