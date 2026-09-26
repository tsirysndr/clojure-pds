(ns pds.syntax-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pds.protocol.syntax :as syntax]))

(deftest upstream-syntax-fixtures
  (doseq [[kind valid?] {"handle" syntax/handle? "did" syntax/did? "nsid" syntax/nsid?
                         "tid" syntax/tid? "recordkey" syntax/record-key?
                         "atidentifier" syntax/at-identifier? "aturi" syntax/at-uri?}
          [outcome expected] [["valid" true] ["invalid" false]]
          line (str/split-lines (slurp (io/resource (str "fixtures/syntax/" kind "_syntax_" outcome ".txt"))))
          :when (and (not (str/blank? line)) (not (str/starts-with? line "#")))]
    (testing (str kind " " outcome " " (pr-str line))
      ;; One older upstream "valid" NSID exceeds the current authority limit.
      ;; Preserve the original fixture, but enforce the current spec's 253 cap.
      (is (= (if (and (= kind "nsid") (= outcome "valid")
                      (> (.lastIndexOf ^String line ".") 253)) false expected)
             (valid? line))))))

(deftest monotonic-tids
  (doseq [value [0 1 1023 1024 1720000000000000000 Long/MAX_VALUE]]
    (is (= value (syntax/decode-tid (syntax/encode-tid value)))))
  (let [clock (atom 1700000000000000)
        next-tid (syntax/tid-generator #(deref clock) 5)
        a (next-tid)
        b (next-tid)]
    (swap! clock - 10000)
    (let [c (next-tid)]
      (is (neg? (compare a b)))
      (is (neg? (compare b c)))
      (is (neg? (compare c (next-tid c)))))))
