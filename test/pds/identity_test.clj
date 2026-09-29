(ns pds.identity-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pds.crypto :as crypto]
            [pds.identity :as identity]
            [pds.plc :as plc]
            [pds.plc-test :as plc-test]
            [pds.protocol.codec :as codec])
  (:import [java.util.concurrent Semaphore]))

(def alice "did:plc:ewvi7nxzyoun6zhxrhs64oiz")
(def bob "did:web:bob.example.com")
(defn error [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))
(defn response [value] {:status 200 :body (codec/utf8 value)})

(deftest handle-dns-precedence-conflicts-and-fallback
  (let [calls (atom [])
        records (atom [(str "did=" alice)])
        r (identity/resolver {:txt-lookup (fn [name] (swap! calls conj [:dns name]) @records)
                              :fetch (fn [url options] (swap! calls conj [:http url options]) (response (str " \n" bob "\t\n")))})]
    (is (= alice (identity/resolve-handle! r "Alice.Example.COM")))
    (is (= [[:dns "_atproto.alice.example.com."]] @calls))
    (reset! records [(str "did=" alice) (str "did=" alice) "unrelated=record" "did=invalid"])
    (is (= alice (identity/resolve-handle! r "alice.example.com")) "Duplicate identical DID records are allowed")
    (reset! records [(str "did=" alice) (str "did=" bob)])
    (is (= "HandleNotFound" (error #(identity/resolve-handle! r "alice.example.com"))))
    (is (every? #(= :dns (first %)) @calls) "HTTPS must not override conflicting DNS claims")
    (reset! records ["unrelated=record" "did=not-a-did"])
    (is (= bob (identity/resolve-handle! r "bob.example.com"))))
  (let [calls (atom [])
        r (identity/resolver {:txt-lookup (fn [_] (throw (java.io.IOException. "DNS unavailable")))
                              :fetch (fn [url _] (swap! calls conj url) (response alice))})]
    (is (= alice (identity/resolve-handle! r "alice.example.com")))
    (is (= ["https://alice.example.com/.well-known/atproto-did"] @calls)))
  (let [long-handle (str/join "." (repeat 4 (apply str (repeat 62 "a"))))
        r (identity/resolver {:txt-lookup (fn [_] (is false "Overlong _atproto DNS name must not be queried"))
                              :fetch (fn [_ _] (response alice))})]
    (is (= 251 (count long-handle)))
    (is (= alice (identity/resolve-handle! r long-handle)))))

(deftest shared-user-domain-treats-only-proven-names-as-taken
  (let [settings {:shared-user-domain true
                  :txt-lookup (fn [_] [(str "did=" alice)])
                  :fetch (fn [_ _] (is false "DNS already answered"))}]
    (is (identity/claimed-elsewhere? settings nil "alice.example.com"))
    (is (not (identity/claimed-elsewhere? settings alice "alice.example.com"))
        "A name this DID already owns is not a collision"))
  (let [calls (atom [])
        settings {:shared-user-domain false
                  :txt-lookup (fn [_] (swap! calls conj :dns) [(str "did=" alice)])
                  :fetch (fn [_ _] (swap! calls conj :http) (response alice))}]
    (is (not (identity/claimed-elsewhere? settings nil "alice.example.com")))
    (is (empty? @calls) "A domain this PDS owns alone must not be resolved"))
  ;; A wildcard address record without a matching certificate fails the HTTPS
  ;; fallback, which leaves the name unproven rather than taken.
  (doseq [fetch [(fn [_ _] (throw (java.io.IOException. "no certificate")))
                 (fn [_ _] {:status 404 :body (codec/utf8 "")})
                 (fn [_ _] (response "not-a-did"))]]
    (is (not (identity/claimed-elsewhere?
               {:shared-user-domain true
                :txt-lookup (fn [_] (throw (java.io.IOException. "NXDOMAIN")))
                :fetch fetch}
               nil "free.example.com")))))

(deftest identity-input-policies-and-sanitized-failures
  (let [r (identity/resolver {:fetch (fn [_ _] (is false "No outbound fetch expected"))
                              :txt-lookup (fn [_] (is false "No DNS lookup expected"))})]
    (doseq [handle [nil "bad" "http://example.com" "127.0.0.1" "example.com/" " alice.example.com"]]
      (is (= "InvalidRequest" (error #(identity/resolve-handle! r handle)))))
    (doseq [handle ["alice.localhost" "alice.test" "alice.arpa" "handle.invalid" "alice.onion" "alice.internal" "alice.alt"]]
      (is (= "HandleNotFound" (error #(identity/resolve-handle! r handle)))))
    (doseq [did ["did:key:abc" "did:plc:bad" "did:web:alice.test" "did:web:example.com:path" "did:web:example.com%3A443"]]
      (is (= "DidNotFound" (error #(identity/resolve-did! r did))))))
  (doseq [[status expected] [[404 "DidNotFound"] [410 "DidDeactivated"] [500 "DidResolutionFailed"]]]
    (let [r (identity/resolver {:fetch (fn [_ _] {:status status})})]
      (is (= expected (error #(identity/resolve-did! r alice))))))
  (doseq [body ["{invalid" "[]" (json/write-str {"id" bob})
                (str "{\"id\":\"" alice "\",\"x\":" (apply str (repeat 65 "[")) "0" (apply str (repeat 65 "]")) "}")]]
    (let [r (identity/resolver {:fetch (fn [_ _] (response body))})]
      (is (= "DidResolutionFailed" (error #(identity/resolve-did! r alice))))))
  (is (= "DidResolutionFailed"
         (error #(identity/resolve-did! (identity/resolver {:fetch (fn [_ _] (throw (ex-info "secret upstream details" {})))}) alice))))
  (doseq [url ["http://plc.example.com" "https://user:password@plc.example.com" "https://plc.example.com/path"
               "https://plc.example.com?query=1" "https://plc.example.com/#fragment"]]
    (is (thrown? Exception (identity/settings {"PDS_PLC_URL" url}))))
  (is (= {:plc-url "https://plc.example.com" :identity-cache-ttl-ms 300000 :identity-cache-size 1024}
         (identity/settings {"PDS_PLC_URL" "https://plc.example.com/"}))))

(deftest did-resolution-and-bidirectional-handles
  (let [alice "did:web:alice.example.com"
        doc (atom {"id" alice "alsoKnownAs" ["https://not-a-handle.example.com" "at://alice.example.com/path" "at://ALICE.example.com" "at://bob.example.com"]})
        calls (atom [])
        handle-did (atom alice)
        r (identity/resolver {:plc-url "https://plc.example.com"
                              :txt-lookup (fn [name] (swap! calls conj name) [(str "did=" @handle-did)])
                              :fetch (fn [url _] (swap! calls conj url) (response (json/write-str @doc)))})]
    (is (= {:did alice :handle "alice.example.com" :didDoc @doc} (identity/resolve-identity! r alice)))
    (is (= ["https://alice.example.com/.well-known/did.json" "_atproto.alice.example.com."] @calls))
    (reset! calls [])
    (reset! handle-did bob)
    (is (= "handle.invalid" (:handle (identity/resolve-identity! r alice))))
    (is (not-any? #(= "_atproto.bob.example.com." %) @calls) "Only the first syntactically valid handle may be considered")
    (reset! doc {"id" bob})
    (reset! calls [])
    (is (= @doc (identity/resolve-did! r bob)))
    (is (= ["https://bob.example.com/.well-known/did.json"] @calls))
    (is (= "handle.invalid" (:handle (identity/resolve-identity! r bob)))))
  (let [doc {"id" "did:web:local.pds.localhost" "alsoKnownAs" ["at://local.pds.localhost"]}
        r (identity/resolver {:local-handle #(when (= % "local.pds.localhost") (get doc "id"))
                              :local-document #(when (= % (get doc "id")) doc)
                              :fetch (fn [_ _] (is false "Hosted identity does not require outbound I/O"))})]
    (is (= {:did (get doc "id") :handle "local.pds.localhost" :didDoc doc}
           (identity/resolve-identity! r "LOCAL.pds.localhost")))))

(deftest plc-resolution-verifies-audit-history-recovery-and-tombstones
  (let [rotation (crypto/keypair "ES256K") recovery (crypto/keypair "ES256")
        genesis (plc/sign-operation (plc-test/unsigned [recovery rotation] rotation) rotation)
        did (plc/genesis-did genesis)
        update (plc-test/update-op genesis rotation {"alsoKnownAs" ["at://disputed.example.com"]})
        recovered (plc-test/update-op genesis recovery {"alsoKnownAs" ["at://recovered.example.com"]})
        row #(plc-test/row did %1 %2 %3)
        entries [(row genesis "2026-01-01T00:00:00Z" false)
                 (row update "2026-01-01T01:00:00Z" true)
                 (row recovered "2026-01-01T02:00:00Z" false)]
        audit (atom entries) calls (atom [])
        resolver (identity/resolver {:plc-url "https://directory.example.com"
                                     :txt-lookup (fn [_] [(str "did=" did)])
                                     :fetch (fn [url options] (swap! calls conj [url options])
                                              (response (json/write-str @audit)))})]
    (is (= "recovered.example.com" (:handle (identity/resolve-identity! resolver did))))
    (is (= (plc/did-document (plc/operation-data did recovered)) (identity/resolve-did! resolver did)))
    (is (every? #(= [(str "https://directory.example.com/" did "/log/audit")
                     {:maximum (* 4 1024 1024) :timeout-ms 5000 :redirects 0}] %) @calls))
    (doseq [bad [(assoc-in entries [1 "nullified"] false)
                 (assoc-in entries [2 "operation" "alsoKnownAs"] ["at://forged.example.com"])
                 (subvec entries 1)
                 (assoc-in entries [0 "did"] alice)
                 (plc/did-document (plc/operation-data did recovered))]]
      (reset! audit bad)
      (is (= "DidResolutionFailed" (error #(identity/resolve-did! resolver did)))))
    (reset! audit (conj entries (row (plc-test/tombstone recovered recovery) "2026-01-01T03:00:00Z" false)))
    (is (= "DidDeactivated" (error #(identity/resolve-did! resolver did))))))

(deftest did-key-and-service-selection
  (doseq [algorithm ["ES256" "ES256K"]]
    (let [key (crypto/keypair algorithm)
          entry {"id" "#atproto" "controller" alice "type" "Multikey" "publicKeyMultibase" (crypto/multikey algorithm (:public key))}
          invalid-key (crypto/multikey algorithm (byte-array (cons 2 (repeat 32 255))))
          doc {"id" alice "verificationMethod" [(assoc entry "controller" bob) (assoc entry "id" (str bob "#atproto"))
                                                 (assoc entry "publicKeyMultibase" invalid-key) entry]}]
      (is (thrown? Exception (crypto/parse-multikey invalid-key)))
      (is (= algorithm (:algorithm (identity/signing-key doc))))
      (is (= (vec (:public key)) (vec (:public (identity/signing-key doc)))))
      (is (= algorithm (:algorithm (identity/signing-key {"id" alice "verificationMethod" [(assoc entry "id" (str alice "#atproto"))]}))))))
  (let [entry {"id" "#atproto_pds" "type" "AtprotoPersonalDataServer" "serviceEndpoint" "https://pds.example.com/"}]
    (is (= "https://pds.example.com" (identity/pds-endpoint {"id" alice "service" [(assoc entry "id" (str bob "#atproto_pds")) entry]})))
    (is (nil? (identity/pds-endpoint {"id" alice "service" [(assoc entry "serviceEndpoint" "https://pds.example.com/path") entry]}))
        "Later services cannot override the first matching service")
    (is (nil? (identity/pds-endpoint {"id" alice "service" [false 3 nil]})))))

(deftest resolver-capacity-is-released-on-errors
  (let [r (assoc (identity/resolver {}) :permits (Semaphore. 1))]
    (is (= "ServiceUnavailable" (identity/bounded-call! r #(error (fn [] (identity/bounded-call! r (constantly :unreachable)))))))
    (is (thrown? Exception (identity/bounded-call! r #(throw (Exception. "failed lookup")))))
    (is (= :released (identity/bounded-call! r (constantly :released))))))
