(ns pds.identity
  (:require [clojure.string :as str]
            [pds.crypto :as crypto]
            [pds.dns :as dns]
            [pds.errors :as errors]
            [pds.net :as net]
            [pds.protocol.codec :as codec]
            [pds.protocol.syntax :as syntax]
            [pds.request :as request])
  (:import [java.io ByteArrayInputStream]
           [java.util.concurrent Semaphore]))

(def reserved-tlds #{"alt" "arpa" "example" "internal" "invalid" "local" "localhost" "onion" "test"})
(defn resolvable-handle? [value]
  (and (syntax/handle? value) (not (reserved-tlds (last (str/split (str/lower-case value) #"\."))))))
(defn supported-did? [value]
  (boolean (and (string? value)
                (or (re-matches #"did:plc:[a-z2-7]{24}" value)
                    (and (str/starts-with? value "did:web:") (resolvable-handle? (subs value 8)))))))

(defn origin! [value]
  (let [uri (net/https-uri! value)]
    (when-not (and (#{"" "/"} (.getPath uri)) (nil? (.getQuery uri)))
      (throw (ex-info "Expected an HTTPS origin without path or query" {})))
    (str/replace value #"/$" "")))

(defn settings [env]
  (try {:plc-url (origin! (get env "PDS_PLC_URL" "https://plc.directory"))}
       (catch Exception _ (throw (ex-info "PDS_PLC_URL must be an HTTPS origin" {:variable "PDS_PLC_URL"})))))

(defn resolver
  "Explicit dependencies keep network resolution testable. Hosted lookups must
  finish their database reads before returning; no transaction spans remote I/O."
  [{:keys [http-client fetch txt-lookup local-handle local-document plc-url]
    :or {local-handle (constantly nil) local-document (constantly nil) plc-url "https://plc.directory"}}]
  (let [lookup (delay (dns/txt-lookup))]
    {:fetch (or fetch (fn [url options]
                       (when-not http-client (errors/raise! 503 "ServiceUnavailable" "Identity resolver is unavailable"))
                       (net/fetch! http-client url options)))
     :txt-lookup (or txt-lookup #(@lookup %))
     :local-handle local-handle :local-document local-document :plc-url (origin! plc-url)
     :permits (Semaphore. 32)}))

(defn- fetch! [resolver url options error]
  (try ((:fetch resolver) url options)
       (catch Exception e
         (if (:xrpc (ex-data e)) (throw e)
             (errors/raise! 502 error "Remote identity request failed")))))

(defn resolve-handle! [resolver handle]
  (when-not (syntax/handle? handle) (errors/invalid! "Invalid handle"))
  (let [handle (str/lower-case handle)]
    (or ((:local-handle resolver) handle)
        (do
          (when-not (resolvable-handle? handle) (errors/raise! 400 "HandleNotFound" "Handle cannot be resolved"))
          (let [records (when (<= (+ 9 (count handle)) 253)
                          (try ((:txt-lookup resolver) (str "_atproto." handle ".")) (catch Exception _ [])))
                candidates (set (keep #(when (and (string? %) (str/starts-with? % "did=")
                                                  (supported-did? (subs % 4))) (subs % 4)) records))]
            (when (> (count candidates) 1) (errors/raise! 400 "HandleNotFound" "Handle has conflicting DNS records"))
            (or (first candidates)
                (let [{:keys [status body]} (fetch! resolver (str "https://" handle "/.well-known/atproto-did")
                                                   {:maximum 8192 :timeout-ms 5000} "HandleResolutionFailed")]
                  (when (= 404 status) (errors/raise! 400 "HandleNotFound" "Handle was not found"))
                  (when-not (= 200 status) (errors/raise! 502 "HandleResolutionFailed" "Handle endpoint returned an unexpected status"))
                  (let [did (try (str/trim (codec/text body)) (catch Exception _ nil))]
                    (when-not (supported-did? did) (errors/raise! 400 "HandleNotFound" "Handle has no supported DID"))
                    did))))))))

(defn resolve-did! [resolver did]
  (when-not (syntax/did? did) (errors/invalid! "Invalid DID"))
  (or ((:local-document resolver) did)
      (do
        (when-not (supported-did? did) (errors/raise! 400 "DidNotFound" "Unsupported DID"))
        (let [url (if (str/starts-with? did "did:plc:") (str (:plc-url resolver) "/" did)
                      (str "https://" (subs did 8) "/.well-known/did.json"))
              {:keys [status body]} (fetch! resolver url {:maximum 1048576 :timeout-ms 5000} "DidResolutionFailed")]
          (when (= 404 status) (errors/raise! 400 "DidNotFound" "DID was not found"))
          (when (= 410 status) (errors/raise! 400 "DidDeactivated" "DID is deactivated"))
          (when-not (= 200 status) (errors/raise! 502 "DidResolutionFailed" "DID endpoint returned an unexpected status"))
          (let [document (try (request/json-body {:headers {"content-type" "application/json"}
                                                  :body (ByteArrayInputStream. body)})
                              (catch Exception _ (errors/raise! 502 "DidResolutionFailed" "Invalid DID document")))]
            (when-not (= did (get document "id")) (errors/raise! 502 "DidResolutionFailed" "DID document identifier mismatch"))
            document)))))

(defn claimed-handle [document]
  (when (vector? (get document "alsoKnownAs"))
    (some #(when (and (string? %) (str/starts-with? % "at://") (syntax/handle? (subs % 5)))
             (str/lower-case (subs % 5))) (get document "alsoKnownAs"))))

(defn signing-key [document]
  (when (vector? (get document "verificationMethod"))
    (some (fn [entry]
            (when (and (= (get document "id") (get entry "controller"))
                       (#{"#atproto" (str (get document "id") "#atproto")} (get entry "id"))
                       (= "Multikey" (get entry "type")))
              (try (crypto/parse-multikey (get entry "publicKeyMultibase")) (catch Exception _ nil))))
          (get document "verificationMethod"))))

(defn pds-endpoint [document]
  (when (vector? (get document "service"))
    (let [entry (some #(when (and (#{"#atproto_pds" (str (get document "id") "#atproto_pds")} (get % "id"))
                                   (= "AtprotoPersonalDataServer" (get % "type"))) %) (get document "service"))]
      (try (origin! (get entry "serviceEndpoint")) (catch Exception _ nil)))))

(defn resolve-identity! [resolver identifier]
  (when-not (syntax/at-identifier? identifier) (errors/invalid! "Invalid identifier"))
  (let [did (if (syntax/did? identifier) identifier (resolve-handle! resolver identifier))
        document (resolve-did! resolver did)
        handle (claimed-handle document)
        verified? (and handle (try (= did (resolve-handle! resolver handle)) (catch Exception _ false)))]
    {:did did :handle (if verified? handle "handle.invalid") :didDoc document}))

(defn bounded-call! [resolver f]
  (let [^Semaphore permits (:permits resolver)]
    (when-not (.tryAcquire permits) (errors/raise! 503 "ServiceUnavailable" "Identity resolver is busy"))
    (try (f) (finally (.release permits)))))
