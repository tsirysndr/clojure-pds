(ns pds.account-admin-test
  (:require [clojure.test :refer [deftest is]]
            [pds.account-admin :as cli]))
(deftest validates-operator-commands-before-opening-dependencies
  (let [did "did:web:alice.example.com"]
    (is (= {:command "status" :did did :version nil :reference nil} (cli/command! ["status" did])))
    (is (= {:command "recover-authenticators" :did did :version 42 :reference "case-42"}
           (cli/command! ["recover-authenticators" did "42" "case-42"])))
    (doseq [args [[] ["status"] ["status" "alice.example.com"] ["status" did "extra"]
                  ["recover-authenticators" did "-1" "case"] ["recover-authenticators" did "1.0" "case"]
                  ["recover-authenticators" did "9223372036854775808" "case"] ["recover-authenticators" did "1" ""]
                  ["recover-authenticators" did "1" "a\nsecret"] ["recover-authenticators" did "1" (apply str (repeat 129 "x"))]]]
      (is (= 64 (:exit (cli/run! args {})))))
    (doseq [password [nil "tiny" (apply str (repeat 1025 "x"))]]
      (is (= "InvalidPasswordConfiguration" (get-in (cli/run! ["recover-authenticators" did "1" "case"]
                                                                             {"PDS_RECOVERY_PASSWORD" password}) [:result :error]))))
    (let [result (cli/run! ["status" did] {"PDS_DATABASE_URL" "secret-invalid-url"})]
      (is (= "AccountRecoveryFailed" (get-in result [:result :error])))
      (is (not (.contains (pr-str result) "secret-invalid-url"))))))
