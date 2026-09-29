(ns pds.identity
  (:require [clojure.string :as str]
            [pds.crypto :as crypto]
            [pds.dns :as dns]
            [pds.errors :as errors]
            [pds.identity.cache :as cache]
            [pds.net :as net]
            [pds.plc :as plc]
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
  (merge (cache/settings env)
         (try {:plc-url (origin! (get env "PDS_PLC_URL" "https://plc.directory"))}
              (catch Exception _ (throw (ex-info "PDS_PLC_URL must be an HTTPS origin" {:variable "PDS_PLC_URL"}))))))

(defn resolver
  "Explicit dependencies keep network resolution testable. Hosted lookups must
  finish their database reads before returning; no transaction spans remote I/O.
  Internal resolvers remain uncached unless explicitly given an identity-cache."
  [{:keys [http-client fetch txt-lookup local-handle local-document plc-url identity-cache]
    :or {local-handle (constantly nil) local-document (constantly nil) plc-url "https://plc.directory"}}]
  (let [lookup (delay (dns/txt-lookup))]
    {:fetch (or fetch (fn [url options]
                       (when-not http-client (errors/raise! 503 "ServiceUnavailable" "Identity resolver is unavailable"))
                       (net/fetch! http-client url options)))
     :txt-lookup (or txt-lookup #(@lookup %))
     :local-handle local-handle :local-document local-document :plc-url (origin! plc-url)
     :identity-cache identity-cache
     :permits (Semaphore. 32)}))

(defn- fetch! [resolver url options error]
  (try ((:fetch resolver) url options)
       (catch Exception e
         (if (:xrpc (ex-data e)) (throw e)
             (errors/raise! 502 error "Remote identity request failed")))))

(defn- remote-value! [resolver key load-value]
  (if-let [memo (:refresh-memo resolver)]
    (if-let [entry (find @memo key)] (val entry)
      (let [value (cache/lookup! (:identity-cache resolver) key true load-value)]
        (swap! memo assoc key value) value))
    (cache/lookup! (:identity-cache resolver) key false load-value)))

(defn resolve-handle! [resolver handle]
  (when-not (syntax/handle? handle) (errors/invalid! "Invalid handle"))
  (let [handle (str/lower-case handle)]
    (or ((:local-handle resolver) handle)
        (do
          (when-not (resolvable-handle? handle) (errors/raise! 400 "HandleNotFound" "Handle cannot be resolved"))
          (remote-value! resolver [:handle handle]
           (fn []
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
                    did))))))))))

(defn claimed-elsewhere?
  "True when a handle in a user domain shared with other PDSes already resolves
  to a different DID. Only a successful resolution proves a name is taken: a
  lookup that fails leaves it unproven, so an unreachable peer or a wildcard
  record without a certificate cannot block signups."
  [settings did handle]
  (boolean
    (when (:shared-user-domain settings)
      (let [lookup (resolver (select-keys settings [:http-client :txt-lookup :fetch]))]
        (try (when-let [owner (resolve-handle! lookup handle)] (not= owner did))
             (catch Exception _ false))))))

(defn resolve-did! [resolver did]
  (when-not (syntax/did? did) (errors/invalid! "Invalid DID"))
  (or ((:local-document resolver) did)
      (do
        (when-not (supported-did? did) (errors/raise! 400 "DidNotFound" "Unsupported DID"))
        (remote-value! resolver [:did did]
         (fn []
          (let [plc? (str/starts-with? did "did:plc:")
              url (if plc? (str (:plc-url resolver) "/" did "/log/audit")
                      (str "https://" (subs did 8) "/.well-known/did.json"))
              options (if plc? {:maximum (* 4 1024 1024) :timeout-ms 5000 :redirects 0}
                               {:maximum 1048576 :timeout-ms 5000})
              {:keys [status body]} (fetch! resolver url options "DidResolutionFailed")]
          (when (= 404 status) (errors/raise! 400 "DidNotFound" "DID was not found"))
          (when (= 410 status) (errors/raise! 400 "DidDeactivated" "DID is deactivated"))
          (when-not (= 200 status) (errors/raise! 502 "DidResolutionFailed" "DID endpoint returned an unexpected status"))
          (let [document (try (if plc?
                               (plc/did-document (:data (plc/verify-audit! did (request/json-value body))))
                               (request/json-body {:headers {"content-type" "application/json"}
                                                   :body (ByteArrayInputStream. body)}))
                              (catch Exception _ (errors/raise! 502 "DidResolutionFailed" "Invalid DID document or PLC audit")))]
            (when (and plc? (nil? document)) (errors/raise! 400 "DidDeactivated" "PLC DID is tombstoned"))
            (when-not (= did (get document "id")) (errors/raise! 502 "DidResolutionFailed" "DID document identifier mismatch"))
            document)))))))

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

(defn refresh-identity! [resolver identifier]
  (when-not (syntax/at-identifier? identifier) (errors/invalid! "Invalid identifier"))
  (let [handle (when-not (syntax/did? identifier) (str/lower-case identifier))]
    (cache/invalidate! (:identity-cache resolver)
      (fn [entries]
        (let [did (if handle (get-in entries [[:handle handle] :value]) identifier)
              claimed (claimed-handle (get-in entries [[:did did] :value]))]
          (into (set [[:did did] [:handle handle] [:handle claimed]])
                (keep (fn [[key entry]] (when (and did (= :handle (first key)) (= did (:value entry))) key))) entries))))
    ;; Every remote binding visited by the bidirectional check is fetched anew.
    ;; Memoization only avoids fetching the same handle twice in this request.
    (resolve-identity! (assoc resolver :refresh-memo (atom {})) identifier)))

(defn bounded-call! [resolver f]
  (let [^Semaphore permits (:permits resolver)]
    (when-not (.tryAcquire permits) (errors/raise! 503 "ServiceUnavailable" "Identity resolver is busy"))
    (try (f) (finally (.release permits)))))
