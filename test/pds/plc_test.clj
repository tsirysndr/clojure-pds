(ns pds.plc-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [pds.crypto :as crypto]
            [pds.plc :as plc]
            [pds.protocol.codec :as codec])
  (:import [java.math BigInteger]
           [java.nio.file Files]
           [java.util Arrays]
           [java.util.concurrent TimeUnit]
           [org.bouncycastle.util BigIntegers]))

(defn unsigned [rotation signing]
  {"type" "plc_operation" "prev" nil "rotationKeys" (mapv plc/did-key rotation)
   "verificationMethods" {"atproto" (plc/did-key signing)} "alsoKnownAs" ["at://alice.example.com"]
   "services" {"atproto_pds" {"type" "AtprotoPersonalDataServer" "endpoint" "https://pds.example.com"}}})
(defn update-op [op key changes]
  (plc/sign-operation (merge (dissoc (plc/normalize op) "sig") {"prev" (plc/operation-cid op)} changes) key))
(defn tombstone [op key] (plc/sign-operation {"type" "plc_tombstone" "prev" (plc/operation-cid op)} key))
(defn row [did op time nullified]
  {"did" did "operation" op "cid" (plc/operation-cid op) "createdAt" time "nullified" nullified})

(deftest genesis-rotation-and-tombstone-signatures
  (doseq [algorithm ["ES256" "ES256K"]]
    (let [rotation (crypto/keypair algorithm) signing (crypto/keypair "ES256") next-key (crypto/keypair algorithm)
          genesis (plc/sign-operation (unsigned [rotation] signing) rotation)
          did (plc/genesis-did genesis)
          update (update-op genesis rotation {"rotationKeys" [(plc/did-key next-key)] "alsoKnownAs" ["at://renamed.example.com"]})
          deleted (tombstone update next-key)
          state (plc/verify-log! did [genesis update])]
      (is (re-matches #"did:plc:[a-z2-7]{24}" did))
      (is (= genesis (plc/verify-genesis! did genesis)))
      (is (= genesis (plc/sign-operation (unsigned [rotation] signing) rotation)) "Deterministic signatures yield the same DID")
      (is (= [(plc/did-key next-key)] (get-in state [:data "rotationKeys"])))
      (is (= ["at://renamed.example.com"] (get-in state [:data "alsoKnownAs"])))
      (is (= (plc/operation-cid update) (:head state)))
      (is (= (plc/did-key rotation) (plc/signer! [(plc/did-key rotation)] update)))
      (is (= (subs (plc/did-key signing) 8) (get-in (plc/did-document (:data state)) ["verificationMethod" 0 "publicKeyMultibase"])))
      (is (= {:head (plc/operation-cid deleted) :data nil} (plc/verify-log! did [genesis update deleted])))
      (is (thrown? Exception (plc/verify-genesis! "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa" genesis)))
      (is (thrown? Exception (plc/verify-log! did [genesis (update-op genesis signing {})])))
      (is (thrown? Exception (plc/verify-log! did [genesis update (tombstone update rotation)])))
      (is (thrown? Exception (plc/verify-log! did [genesis deleted])))
      (is (thrown? Exception (plc/verify-log! did [genesis update deleted (update-op update next-key {})])))
      (is (thrown? Exception (plc/verify-log! did [genesis (assoc update "alsoKnownAs" ["at://attacker.example.com"])]))))))

(deftest legacy-genesis-remains-signed-and-hashed-in-its-original-form
  (let [recovery (crypto/keypair "ES256K") signing (crypto/keypair "ES256")
        genesis (plc/sign-operation {"type" "create" "prev" nil "signingKey" (plc/did-key signing)
                                     "recoveryKey" (plc/did-key recovery) "handle" "alice.example.com" "service" "pds.example.com"} signing)
        did (plc/genesis-did genesis)
        update (update-op genesis recovery {"alsoKnownAs" ["at://new.example.com"]})]
    (is (= genesis (plc/verify-genesis! did genesis)))
    (is (not= did (plc/genesis-did (plc/normalize genesis))))
    (is (= ["at://new.example.com"] (get-in (plc/verify-log! did [genesis update]) [:data "alsoKnownAs"])))
    (is (= "https://pds.example.com" (get-in (plc/verify-log! did [genesis]) [:data "services" "atproto_pds" "endpoint"])))
    (is (thrown? Exception (plc/verify-genesis! did (plc/normalize genesis))))))

(deftest strict-operation-and-signature-encoding
  (let [key (crypto/keypair "ES256K") base (unsigned [key] key) signed (plc/sign-operation base key)]
    (doseq [bad [(dissoc base "prev") (assoc base "extra" true) (assoc base "rotationKeys" [])
                 (assoc base "rotationKeys" (vec (repeat 6 (plc/did-key key))))
                 (assoc base "rotationKeys" [(plc/did-key key) (plc/did-key key)])
                 (assoc base "rotationKeys" ["did:key:zInvalid0"])
                 (assoc base "verificationMethods" {"atproto" "did:web:example.com"})
                 (assoc base "alsoKnownAs" [false]) (assoc base "services" {"pds" {"type" "test" "endpoint" "https://example.com" "unknown" 1}})
                 (assoc base "prev" (codec/link (plc/operation-cid signed)))
                 (assoc base "prev" (codec/cid 85 (byte-array [1])))
                 (assoc base "alsoKnownAs" [(apply str (repeat 7500 "a"))])]]
      (is (thrown? Exception (plc/sign-operation bad key))))
    (let [sig (get signed "sig") alphabet "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
          noncanonical (str (subs sig 0 85) (.charAt alphabet (inc (.indexOf alphabet (int (last sig))))))]
      (doseq [bad [(str sig "=") noncanonical (subs sig 1) "not-base64"]]
        (is (thrown? Exception (plc/operation! (assoc signed "sig" bad))))))
    (let [raw (crypto/unb64 (get signed "sig"))
          low (BigInteger. 1 (Arrays/copyOfRange raw 32 64))
          high (.subtract (.getN (crypto/domain "ES256K")) low)
          changed (byte-array (concat (take 32 raw) (BigIntegers/asUnsignedByteArray 32 high)))
          bad (assoc signed "sig" (crypto/b64 changed))]
      (is (thrown? Exception (plc/signer! [(plc/did-key key)] bad)) "High-S malleability is rejected"))))

(deftest audit-recovery-priority-window-and-nullification
  (let [recovery (crypto/keypair "ES256K") ordinary (crypto/keypair "ES256") replacement (crypto/keypair "ES256K")
        genesis (plc/sign-operation (unsigned [recovery ordinary] ordinary) ordinary)
        did (plc/genesis-did genesis)
        changed (update-op genesis ordinary {"rotationKeys" [(plc/did-key ordinary)]})
        deleted (tombstone changed ordinary)
        recovered (update-op genesis recovery {"rotationKeys" [(plc/did-key replacement)]})
        audit [(row did genesis "2026-01-01T00:00:00Z" false)
               (row did changed "2026-01-01T01:00:00Z" true)
               (row did deleted "2026-01-01T02:00:00Z" true)
               (row did recovered "2026-01-04T01:00:00Z" false)]
        result (plc/verify-audit! did audit)]
    (is (= (plc/operation-cid recovered) (:head result)))
    (is (= #{(plc/operation-cid changed) (plc/operation-cid deleted)} (:nullified result)))
    (is (= [(plc/did-key replacement)] (get-in result [:data "rotationKeys"])))
    (is (thrown? Exception (plc/verify-audit! did (assoc-in audit [3 "createdAt"] "2026-01-04T01:00:00.001Z"))))
    (is (thrown? Exception (plc/verify-audit! did (assoc-in audit [3 "createdAt"] "2026-01-01T02:00:00Z"))))
    (is (thrown? Exception (plc/verify-audit! did (assoc-in audit [1 "nullified"] false))))
    (is (thrown? Exception (plc/verify-audit! did (assoc-in audit [2 "cid"] (plc/operation-cid changed)))))
    (is (thrown? Exception (plc/verify-audit! did (assoc audit 3 (row did (update-op genesis ordinary {}) "2026-01-02T00:00:00Z" false)))))
    (is (thrown? Exception (plc/verify-audit! did (conj audit (row did (update-op changed recovery {}) "2026-01-05T00:00:00Z" false))))
        "Cannot extend an already-nullified operation")))

(deftest upstream-plc-operations-in-both-directions
  (when (= "true" (System/getenv "PDS_TEST_UPSTREAM"))
    (let [key (crypto/keypair "ES256K") signing (crypto/keypair "ES256")
          genesis (plc/sign-operation (unsigned [key] signing) key)
          update (update-op genesis key {"alsoKnownAs" ["at://updated.example.com"]})
          ops [genesis update (tombstone update key)]
          did (plc/genesis-did genesis)
          recovery-genesis (plc/sign-operation (unsigned [key signing] signing) signing)
          recovery-did (plc/genesis-did recovery-genesis)
          disputed (update-op recovery-genesis signing {"rotationKeys" [(plc/did-key signing)]})
          recovered (update-op recovery-genesis key {"rotationKeys" [(plc/did-key key)]})
          audit [(row recovery-did recovery-genesis "2026-01-01T00:00:00Z" false)
                 (row recovery-did disputed "2026-01-01T01:00:00Z" true)
                 (row recovery-did recovered "2026-01-04T01:00:00Z" false)]
          path (Files/createTempFile "pds-plc-" ".json" (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (spit (str path) (json/write-str {:did did :operations ops :cids (mapv plc/operation-cid ops)
                                         :audit audit :auditHead (:head (plc/verify-audit! recovery-did audit))
                                         :activeData (:data (plc/verify-log! did [genesis update]))}))
        (let [process (.start (doto (ProcessBuilder. ["node" "scripts/conformance/verify-plc.mjs" (str path)]) (.redirectErrorStream true)))
              finished? (.waitFor process 30 TimeUnit/SECONDS)]
          (when-not finished? (.destroyForcibly process))
          (is finished?)
          (when finished?
            (let [output (slurp (.getInputStream process))]
              (is (= 0 (.exitValue process)) output)
              (when (zero? (.exitValue process))
                (doseq [fixture (json/read-str output)]
                  (is (= (get fixture "head") (:head (plc/verify-log! (get fixture "did") (get fixture "operations"))))))))))
        (finally (Files/deleteIfExists path))))))
