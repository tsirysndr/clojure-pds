(ns pds.crypto-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [pds.crypto :as crypto]
            [pds.protocol.codec :as codec])
  (:import [java.util Base64]))

(deftest upstream-signatures
  (doseq [f (json/read-str (slurp (io/resource "fixtures/crypto/signature-fixtures.json")))]
    (testing (get f "comment")
      (let [key (crypto/parse-multikey (subs (get f "publicKeyDid") 8))
            decode #(.decode (Base64/getDecoder) ^String %)]
        (is (= (get f "validSignature")
               (crypto/verify (:algorithm key) (:public key) (decode (get f "messageBase64"))
                              (decode (get f "signatureBase64")))))))))

(deftest signing-and-encryption
  (doseq [algorithm ["ES256" "ES256K"]]
    (let [{:keys [private public]} (crypto/keypair algorithm)
          message (codec/utf8 "test") signature (crypto/sign algorithm private message)]
      (is (crypto/verify algorithm public message signature))
      (is (not (crypto/verify algorithm public (codec/utf8 "changed") signature)))
      (is (= (vec public) (vec (:public (crypto/parse-multikey (crypto/multikey algorithm public))))))))
  (let [key (crypto/random-bytes 32) plain (crypto/random-bytes 32)
        sealed (crypto/seal key "did:web:alice.example.com" plain)]
    (is (= (vec plain) (vec (crypto/unseal key "did:web:alice.example.com" sealed))))
    (is (thrown? Exception (crypto/unseal key "did:web:bob.example.com" sealed))))
  (let [hash (crypto/password-hash "correct-password")]
    (is (crypto/password-matches? "correct-password" hash))
    (is (not (crypto/password-matches? "incorrect-password" hash)))
    (is (not= hash (crypto/password-hash "correct-password")))))
