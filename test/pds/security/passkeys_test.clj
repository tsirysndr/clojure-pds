(ns pds.security.passkeys-test
  (:require [clojure.test :refer [deftest is testing]]
            [pds.security.passkeys :as passkeys]))

(deftest configured-origin-is-the-only-rp-authority
  (is (= {:origin "https://pds.example.com" :rp-id "pds.example.com"
          :origins #{"https://pds.example.com"}}
         (passkeys/rp-origin {:public-url "https://pds.example.com" :hostname "unrelated.example.com"})))
  (is (= {:origin "http://localhost:3000" :rp-id "localhost"
          :origins #{"http://localhost:3000"}}
         (passkeys/rp-origin {:public-url "http://localhost:3000"})))
  (doseq [url [nil "http://pds.example.com" "https://pds.example.com/" "https://pds.example.com?other"
               "https://user@pds.example.com" "https://pds.example.com#other" "https://pds.example.com/path"]]
    (is (thrown? Exception (passkeys/rp-origin {:public-url url})))))

(deftest relying-party-follows-configuration
  (testing "defaults to this host, so nothing changes unless configured"
    (let [{:keys [rp-id origins]} (passkeys/rp-origin {:public-url "https://pds.example.com"})]
      (is (= "pds.example.com" rp-id))
      (is (= #{"https://pds.example.com"} origins))))

  (testing "can be the parent shared with a sign-in page in front of it"
    ;; A browser only uses a credential whose RP ID is the page's own domain or
    ;; a parent of it, so one registered under the node host can never be used
    ;; from the parent. The shared parent works from both.
    (let [{:keys [rp-id origins]} (passkeys/rp-origin
                                    {:public-url "https://orangepi.rocksky.social"
                                     :webauthn-rp-id "rocksky.social"
                                     :webauthn-origins ["https://rocksky.social"]})]
      (is (= "rocksky.social" rp-id))
      (is (= #{"https://orangepi.rocksky.social" "https://rocksky.social"} origins))))

  (testing "is refused when it is not this host or a parent of it"
    ;; Otherwise a server could claim credentials for a domain it does not
    ;; answer for.
    (doseq [bad ["example.com" "social" "ocksky.social" "sub.orangepi.rocksky.social"]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (passkeys/rp-origin {:public-url "https://orangepi.rocksky.social"
                                        :webauthn-rp-id bad}))
          (str bad " must not be accepted")))))
