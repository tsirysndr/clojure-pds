(ns pds.security.totp-test
  (:require [clojure.test :refer [deftest is]]
            [pds.protocol.codec :as codec]
            [pds.security.totp :as totp])
  (:import [java.util Locale]))

(deftest official-hotp-and-totp-vectors
  (let [seed (codec/utf8 "12345678901234567890")]
    (doseq [[step expected] (map-indexed vector ["755224" "287082" "359152" "969429" "338314" "254676" "287922" "162583" "399871" "520489"])]
      (is (= expected (totp/code seed step))))
    (doseq [[seconds sha1 sha256 sha512]
            [[59 "94287082" "46119246" "90693936"]
             [1111111109 "07081804" "68084774" "25091201"]
             [1111111111 "14050471" "67062674" "99943326"]
             [1234567890 "89005924" "91819424" "93441116"]
             [2000000000 "69279037" "90698825" "38618901"]
             [20000000000 "65353130" "77737706" "47863826"]]]
      (is (= sha1 (totp/code seed (quot seconds 30) "HmacSHA1" 8)))
      (is (= sha256 (totp/code (codec/utf8 "12345678901234567890123456789012") (quot seconds 30) "HmacSHA256" 8)))
      (is (= sha512 (totp/code (codec/utf8 "1234567890123456789012345678901234567890123456789012345678901234") (quot seconds 30) "HmacSHA512" 8))))))

(deftest bounded-window-strict-codes-and-provisioning
  (let [seed (codec/utf8 "12345678901234567890")]
    (doseq [step [9 10 11]] (is (= step (totp/matching-step seed 300 (totp/code seed step)))))
    (doseq [value [nil "" "12345" "1234567" " 123456" "１２３４５６" (totp/code seed 8) (totp/code seed 12)]]
      (is (nil? (totp/matching-step seed 300 value))))
    (is (= 0 (totp/matching-step seed 0 (totp/code seed 0))))
    (is (nil? (totp/matching-step seed -1 "755224")))
    (is (= "otpauth://totp/My%20PDS:alice%40example.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=My%20PDS&algorithm=SHA1&digits=6&period=30"
           (totp/provisioning-uri seed "My PDS" "alice@example.com"))))
  (doseq [label ["" "bad:label" "bad\nlabel"]]
    (is (thrown? Exception (totp/provisioning-uri (totp/secret) label "alice")))))

(deftest codes-use-ascii-digits-under-non-western-locales
  (let [previous (Locale/getDefault)]
    (try
      (Locale/setDefault (Locale/forLanguageTag "ar-EG"))
      (is (= "287082" (totp/code (codec/utf8 "12345678901234567890") 1)))
      (Locale/setDefault (Locale/forLanguageTag "tr-TR"))
      (is (= "NBUQ" (totp/encoded-secret (codec/utf8 "hi"))))
      (finally (Locale/setDefault previous)))))
