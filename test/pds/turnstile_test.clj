(ns pds.turnstile-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [pds.accounts :as accounts]
            [pds.net :as net]
            [pds.turnstile :as turnstile]))

(def settings (merge (turnstile/settings {"PDS_TURNSTILE_SITE_KEY" "site"
                                        "PDS_TURNSTILE_SECRET_KEY" "secret"})
                     {:public-url "https://pds.example.com" :signup-enabled true}))
(def valid {"success" true "hostname" "pds.example.com" "action" "signup"})
(defn response [body] {:status 200 :body (.getBytes (json/write-str body) "UTF-8")})
(defn code [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))

(deftest configuration
  (is (thrown? Exception (turnstile/settings {"PDS_TURNSTILE_SITE_KEY" "site"})))
  (is (thrown? Exception (turnstile/settings {"PDS_TURNSTILE_SECRET_KEY" "secret"})))
  (is (nil? (turnstile/verify! {} nil)))
  (is (= {:siteKey "site" :action "signup"} (turnstile/description settings))))

(deftest rejects-before-account-provisioning
  (doseq [token [nil "" 123 (apply str (repeat 2049 "a"))]]
    (is (= "TurnstileFailed" (code #(accounts/create! nil settings {"turnstileToken" token "inviteCode" "not-a-bypass"}))))))

(deftest validation-is-bound-to-host-and-action
  (doseq [body [(assoc valid "success" false) (assoc valid "success" "true")
                (dissoc valid "hostname") (assoc valid "hostname" "attacker.example")
                (assoc valid "action" "login")]]
    (with-redefs [net/post-json! (fn [& _] (response body))]
      (is (= "TurnstileFailed" (code #(turnstile/verify! settings "token"))))))
  (with-redefs [net/post-json! (fn [_ url body opts]
                                (is (= turnstile/endpoint url))
                                (is (= {"secret" "secret" "response" "token"} (json/read-str (String. ^bytes body "UTF-8"))))
                                (is (= 5000 (:timeout-ms opts)))
                                (response valid))]
    (is (nil? (turnstile/verify! settings "token"))))
  (with-redefs [net/post-json! (fn [& _] (response (assoc valid "hostname" "rocksky.social")))]
    (is (nil? (turnstile/verify! (assoc settings :turnstile-hostnames #{"rocksky.social"}) "token")))))

(deftest provider-failures-fail-closed
  (doseq [f [(fn [& _] (throw (Exception. "timeout")))
            (fn [& _] {:status 503 :body (byte-array 0)})
            (fn [& _] {:status 200 :body (.getBytes "not-json" "UTF-8")})]]
    (with-redefs [net/post-json! f]
      (is (= "TurnstileUnavailable" (code #(turnstile/verify! settings "token")))))))
