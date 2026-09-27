(ns pds.plc-disposition-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [pds.crypto :as crypto]
            [pds.plc :as plc]
            [pds.plc-test :as ops])
  (:import [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(defn cases []
  (let [high (crypto/keypair "ES256K") mid (crypto/keypair "ES256") low (crypto/keypair "ES256K")
        genesis (plc/sign-operation (ops/unsigned [high mid low] low) low)
        did (plc/genesis-did genesis)
        base [(ops/row did genesis "2026-01-01T00:00:00Z" false)]
        queued (ops/update-op genesis mid {"alsoKnownAs" ["at://queued.example.com"]})
        lower (ops/update-op genesis low {})
        same (ops/update-op genesis mid {})
        higher (ops/update-op genesis high {})
        audit (fn [op] (conj base (ops/row did op "2026-01-01T01:00:00Z" false)))
        recovered (conj (assoc-in (audit queued) [1 "nullified"] true)
                        (ops/row did higher "2026-01-01T02:00:00Z" false))]
    [{:did did :entries base :operation queued :expected :unresolved}
     {:did did :entries (audit lower) :operation queued :expected :unresolved}
     {:did did :entries (audit same) :operation queued :expected :superseded}
     {:did did :entries (audit higher) :operation queued :expected :superseded}
     {:did did :entries (audit queued) :operation queued :expected :accepted}
     {:did did :entries (conj (audit queued) (ops/row did (ops/update-op queued low {}) "2026-01-01T02:00:00Z" false))
      :operation queued :expected :accepted}
     {:did did :entries recovered :operation queued :expected :superseded}
     {:did did :entries recovered :operation (ops/update-op queued low {}) :expected :superseded}
     ;; Unseen parent is not proof of cancellation: it might arrive later.
     {:did did :entries base :operation (ops/update-op queued mid {}) :expected :unresolved :unknownParent true}]))

(deftest queued-operation-requires-proof-of-acceptance-or-supersession
  (doseq [{:keys [did entries operation expected]} (cases)]
    (is (= expected (plc/pending-disposition! did entries operation)))))

(deftest invalid-audit-and-unauthorized-operation-are-never-cancellation-proofs
  (let [{:keys [did entries operation]} (first (cases))]
    (is (thrown? Exception (plc/pending-disposition! did (assoc-in entries [0 "nullified"] true) operation)))
    (is (thrown? Exception (plc/pending-disposition! did entries (assoc operation "alsoKnownAs" ["at://tampered.example.com"])))))
  ;; Even an old unresolved operation stays unresolved: no wall-clock cutoff.
  (let [{:keys [did entries operation]} (second (cases))]
    (is (= :unresolved (plc/pending-disposition! did entries operation)))))

(deftest upstream-confirms-delayed-operation-authority
  (when (= "true" (System/getenv "PDS_TEST_UPSTREAM"))
    (let [path (Files/createTempFile "pds-plc-disposition-" ".json" (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (spit (str path) (json/write-str (cases)))
        (let [process (.start (doto (ProcessBuilder. ["node" "scripts/conformance/verify-plc-disposition.mjs" (str path)]) (.redirectErrorStream true)))]
          (try
            (is (.waitFor process 30 TimeUnit/SECONDS))
            (when-not (.isAlive process) (is (zero? (.exitValue process)) (slurp (.getInputStream process))))
            (finally (when (.isAlive process) (.destroyForcibly process)))))
        (finally (Files/deleteIfExists path))))))
