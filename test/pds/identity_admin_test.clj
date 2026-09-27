(ns pds.identity-admin-test
  (:require [clojure.test :refer [deftest is]]
            [pds.identity-admin :as cli]
            [pds.crypto :as crypto]
            [pds.plc :as plc]
            [pds.protocol.codec :as codec]))

(deftest operator-cli-validates-commands-before-opening-dependencies
  (let [did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa" cid (codec/cid (byte-array [1]))]
    (is (= {:command "status" :did did :expected nil} (cli/command! ["status" did])))
    (is (= {:command "rotate-plc-key" :did did :expected cid} (cli/command! ["rotate-plc-key" did cid])))
    (is (= {:command "inspect-plc" :did did :expected nil} (cli/command! ["inspect-plc" did])))
    (is (= {:command "reconcile-plc" :did did :expected cid :queued "-" :remote cid}
           (cli/command! ["reconcile-plc" did cid "-" cid])))
    (is (= {:command "set-recovery-keys" :did did :expected cid :recovery-keys []}
           (cli/command! ["set-recovery-keys" did cid])))
    (let [keys (mapv #(plc/did-key (crypto/keypair %)) ["ES256" "ES256K"])]
      (is (= {:command "set-recovery-keys" :did did :expected cid :recovery-keys keys}
             (cli/command! (into ["set-recovery-keys" did cid] keys))))
      (is (= "InvalidRequest" (get-in (cli/run! ["set-recovery-keys" did cid (first keys) (first keys)] {}) [:result :error]))))
    (let [key (plc/did-key (crypto/keypair "ES256"))]
      (doseq [did [did "did:web:alice.example.com"]]
        (is (= {:command "rotate-signing-key" :did did :expected key} (cli/command! ["rotate-signing-key" did key])))))
    (doseq [args [[] ["delete" did] ["rotate-plc-key" did] ["status" did cid]
                  ["reconcile-plc" did cid cid] ["inspect-plc" did cid] ["set-recovery-keys" did]
                  (into ["set-recovery-keys" did cid] (repeat 5 "key"))]]
      (is (= 64 (:exit (cli/run! args {})))))
    (doseq [args [["status" "did:unsupported:example"] ["rotate-plc-key" did "invalid"]
                  ["rotate-signing-key" did "invalid"] ["reconcile-plc" did cid "bad" cid]
                  ["reconcile-plc" did cid "-" "bad"] ["inspect-plc" "did:web:example.com"]
                  ["set-recovery-keys" did "bad"] ["set-recovery-keys" did cid "private-key-material"]
                  ["rotate-signing-key" did (plc/did-key (crypto/keypair "ES256K"))]]]
      (is (= "InvalidRequest" (get-in (cli/run! args {}) [:result :error]))))
    (let [result (cli/run! ["status" did] {"PDS_DATABASE_URL" "secret-invalid-url"})]
      (is (= 1 (:exit result)))
      (is (= "IdentityOperationFailed" (get-in result [:result :error])))
      (is (not (.contains (pr-str result) "secret-invalid-url"))))))
