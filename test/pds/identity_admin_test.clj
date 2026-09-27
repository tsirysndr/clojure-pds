(ns pds.identity-admin-test
  (:require [clojure.test :refer [deftest is]]
            [pds.identity-admin :as cli]
            [pds.protocol.codec :as codec]))

(deftest operator-cli-validates-commands-before-opening-dependencies
  (let [did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa" cid (codec/cid (byte-array [1]))]
    (is (= {:command "status" :did did :expected nil} (cli/command! ["status" did])))
    (is (= {:command "rotate-plc-key" :did did :expected cid} (cli/command! ["rotate-plc-key" did cid])))
    (doseq [args [[] ["delete" did] ["rotate-plc-key" did] ["status" did cid]]]
      (is (= 64 (:exit (cli/run! args {})))))
    (doseq [args [["status" "did:web:example.com"] ["rotate-plc-key" did "invalid"]]]
      (is (= "InvalidRequest" (get-in (cli/run! args {}) [:result :error]))))
    (let [result (cli/run! ["status" did] {"PDS_DATABASE_URL" "secret-invalid-url"})]
      (is (= 1 (:exit result)))
      (is (= "IdentityOperationFailed" (get-in result [:result :error])))
      (is (not (.contains (pr-str result) "secret-invalid-url"))))))
