(ns pds.oauth.client
  "Fresh, bounded OAuth client metadata/JWKS resolution and redirect policy."
  (:require [clojure.string :as str]
            [pds.net :as net]
            [pds.oauth.jose :as jose]
            [pds.oauth.parameters :as parameters]
            [pds.request :as request])
  (:import [java.net URI]
           [java.util.concurrent Semaphore]))

(defn invalid! [] (throw (ex-info "Invalid OAuth client metadata" {:oauth-error "invalid_client_metadata"})))

(defn uri! [value]
  (try
    (when-not (and (string? value) (<= 1 (count value) 8192)
                   (not (re-find #"[\x00-\x20\x7f\\]" value))) (invalid!))
    (let [uri (URI/create value)]
      (when-not (and (.isAbsolute uri) (nil? (.getRawFragment uri)) (nil? (.getRawUserInfo uri))
                     (or (= -1 (.getPort uri)) (<= 1 (.getPort uri) 65535))) (invalid!))
      uri)
    (catch Exception _ (invalid!))))

(defn https! [value]
  (let [uri (uri! value)]
    (when-not (and (= "https" (.getScheme uri)) (seq (.getHost uri))) (invalid!))
    uri))

(defn client-id! [value]
  (let [uri (uri! value) local? (and (= "http" (.getScheme uri)) (= "localhost" (.getHost uri)))]
    (when-not (and (seq (.getHost uri)) (= (.getHost uri) (.getRawAuthority uri))
                   (or (and local? (#{"" "/"} (.getRawPath uri))) (= "https" (.getScheme uri))))
      (invalid!))
    {:uri uri :development? local?}))

(defn scopes! [value]
  (when-not (and (string? value) (<= 1 (count value) 8192)
                 (re-matches #"[\x21\x23-\x5b\x5d-\x7e]+(?: [\x21\x23-\x5b\x5d-\x7e]+)*" value)) (invalid!))
  (let [tokens (str/split value #" ")]
    (when-not (and (<= (count tokens) 100) (some #{"atproto"} tokens)) (invalid!))
    (set tokens)))

(defn- loopback! [value]
  (let [uri (uri! value)]
    (when-not (and (= "http" (.getScheme uri)) (#{"127.0.0.1" "[::1]"} (.getHost uri))
                   (str/starts-with? (.getRawPath uri) "/")) (invalid!))
    uri))

(defn- origin [^URI uri] [(.getScheme uri) (str/lower-case (.getHost uri)) (.getPort uri)])

(defn redirect-valid! [client-id development? application-type value]
  (let [uri (uri! value)]
    (cond
      development? (loopback! value)
      (= "https" (.getScheme uri))
      (do (https! value)
          (when (= 443 (.getPort uri)) (invalid!))
          (when (and (= "native" application-type) (not= (origin (https! client-id)) (origin uri))) (invalid!)))
      (= "native" application-type)
      (let [scheme (str/join "." (reverse (str/split (str/lower-case (.getHost (https! client-id))) #"\.")))]
        (when-not (and (= scheme (.getScheme uri)) (nil? (.getRawAuthority uri))
                       (some-> (.getRawPath uri) (str/starts-with? "/"))
                       (not (str/starts-with? (.getRawSchemeSpecificPart uri) "//"))) (invalid!)))
      :else (invalid!))
    value))

(defn- string-array? [values allowed required]
  (and (vector? values) (<= 1 (count values) 32) (every? allowed values) (some required values)
       (= (count values) (count (set values)))))

(defn validate! [client-id development? metadata]
  (when-not (map? metadata) (invalid!))
  (let [application (get metadata "application_type" "web")
        method (get metadata "token_endpoint_auth_method" "none")
        redirects (get metadata "redirect_uris")]
    (when-not (and (= client-id (get metadata "client_id")) (#{"web" "native"} application)
                   (#{"none" "private_key_jwt"} method) (true? (get metadata "dpop_bound_access_tokens"))
                   (string-array? (get metadata "grant_types") #{"authorization_code" "refresh_token"} #{"authorization_code"})
                   (string-array? (get metadata "response_types") #{"code"} #{"code"})
                   (vector? redirects) (<= 1 (count redirects) 32) (every? string? redirects)
                   (= (count redirects) (count (set redirects)))
                   (or (not (contains? metadata "token_endpoint_auth_signing_alg"))
                       (= "ES256" (get metadata "token_endpoint_auth_signing_alg")))
                   (not (and (contains? metadata "jwks") (contains? metadata "jwks_uri")))
                   (or (= method "none") (some #(contains? metadata %) ["jwks" "jwks_uri"])))
      (invalid!))
    (scopes! (get metadata "scope"))
    (doseq [redirect redirects] (redirect-valid! client-id development? application redirect))
    (when (contains? metadata "client_name")
      (when-not (and (string? (get metadata "client_name")) (<= (count (get metadata "client_name")) 256)) (invalid!)))
    (doseq [field ["logo_uri" "tos_uri" "policy_uri" "jwks_uri"] :when (contains? metadata field)]
      (https! (get metadata field)))
    (when (contains? metadata "client_uri")
      (let [homepage (uri! (get metadata "client_uri"))]
        (when-not (and (#{"https" "http"} (.getScheme homepage)) (seq (.getHost homepage))
                       (= (str/lower-case (.getHost (https! client-id)))
                          (str/lower-case (.getHost homepage)))) (invalid!))))
    (assoc metadata "application_type" application "token_endpoint_auth_method" method)))

(defn development-metadata! [client-id ^URI uri]
  (let [params (parameters/parse! (or (.getRawQuery uri) "") #{"redirect_uri"})]
    (when-not (every? #{"redirect_uri" "scope"} (keys params)) (invalid!))
    {"client_id" client-id "client_name" "Development client" "application_type" "native"
     "grant_types" ["authorization_code" "refresh_token"] "response_types" ["code"]
     "token_endpoint_auth_method" "none" "dpop_bound_access_tokens" true
     "scope" (get params "scope" "atproto")
     "redirect_uris" (get params "redirect_uri" ["http://127.0.0.1/" "http://[::1]/"])}))

(defn signing-keys! [jwks]
  (let [keys (get jwks "keys")]
    (when-not (and (map? jwks) (vector? keys) (<= 1 (count keys) 32)
                   (every? #(and (map? %) (not-any? (fn [k] (contains? % k)) ["d" "k" "p" "q" "dp" "dq" "qi" "oth"])) keys)
                   (every? #(or (not (contains? % "kid"))
                                (and (string? (get % "kid")) (<= 1 (count (get % "kid")) 256))) keys)
                   (= (count (keep #(get % "kid") keys)) (count (set (keep #(get % "kid") keys))))) (invalid!))
    (let [supported (filter #(and (= "EC" (get % "kty")) (= "P-256" (get % "crv"))
                                  (= "ES256" (get % "alg" "ES256")) (= "sig" (get % "use" "sig"))) keys)]
      (when (empty? supported) (invalid!))
      (mapv #(assoc (jose/public-key! %) :kid (get % "kid") :alg "ES256") supported))))

(defn resolver
  "Owns only concurrency permits. Network lifetime belongs to the caller's client.
  No caching: key removals become visible on the next resolution."
  [{:keys [http-client oauth-client-fetch]}]
  {:permits (Semaphore. 16)
   :fetch (or oauth-client-fetch #(net/exchange! http-client %1 (assoc %2 :method "GET" :headers {"accept" "application/json"})))})

(defn- fetch-json! [resolver url deadline]
  (let [remaining (quot (- deadline (System/nanoTime)) 1000000)]
    (when-not (pos? remaining) (invalid!))
    (let [{:keys [status headers body]} ((:fetch resolver) url {:maximum 65536 :timeout-ms (min 5000 remaining)})
          mime (some-> (get headers "content-type") (str/split #";" 2) first str/trim str/lower-case)]
      (when-not (and (= 200 status) (= "application/json" mime) (bytes? body) (<= (alength ^bytes body) 65536)
                     (or (nil? (get headers "content-encoding")) (= "identity" (str/lower-case (get headers "content-encoding")))))
        (invalid!))
      (let [value (request/json-value body)] (when-not (map? value) (invalid!)) value))))

(defn resolve! [resolver client-id]
  (when-not (.tryAcquire ^Semaphore (:permits resolver))
    (throw (ex-info "OAuth client resolution capacity reached" {:oauth-error "temporarily_unavailable"})))
  (try
    (try
      (let [{:keys [uri development?]} (client-id! client-id)
            deadline (+ (System/nanoTime) 10000000000)
            metadata (validate! client-id development?
                                (if development? (development-metadata! client-id uri) (fetch-json! resolver client-id deadline)))
            jwks (when (contains? metadata "jwks") (get metadata "jwks"))
            keys (cond
                   (contains? metadata "jwks") (signing-keys! jwks)
                   (= "private_key_jwt" (get metadata "token_endpoint_auth_method"))
                   (signing-keys! (fetch-json! resolver (get metadata "jwks_uri") deadline))
                   :else [])]
        {:client-id client-id :development? development? :metadata metadata :keys keys})
      (catch Exception _ (invalid!)))
    (finally (.release ^Semaphore (:permits resolver)))))

(defn redirect! [{:keys [client-id development? metadata]} requested]
  (try
    (redirect-valid! client-id development? (get metadata "application_type") requested)
    (let [same? (if development?
                  (fn [registered]
                    (let [a (loopback! registered) b (loopback! requested)]
                      (= [(.getHost a) (.getRawPath a) (.getRawQuery a)] [(.getHost b) (.getRawPath b) (.getRawQuery b)])))
                  #(= requested %))]
      (when-not (some same? (get metadata "redirect_uris")) (invalid!)))
    requested
    (catch Exception _ (throw (ex-info "Unregistered redirect URI" {:oauth-error "invalid_request"})))))
