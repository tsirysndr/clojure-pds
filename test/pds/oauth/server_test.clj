(ns pds.oauth.server-test
  (:require [clojure.test :refer [deftest is]]
            [pds.oauth.resource :as resource]
            [pds.oauth.server :as server]))

(deftest canonical-origin-is-required-before-discovery-is-published
  (doseq [url ["https://pds.example.com" "https://pds.example.com:8443" "http://localhost:3000" "http://127.0.0.1:3000"]]
    (is (= {:public-url url} (server/validate-origin! {:public-url url}))))
  (doseq [url [nil "" "https://PDS.example.com" "http://pds.example.com" "https://pds.example.com:443"
               "http://localhost:80" "https://pds.example.com/" "https://pds.example.com/path"
               "https://pds.example.com?x=1" "https://pds.example.com#fragment" "https://user@pds.example.com"
               "https://pds.example.com:0" "https://pds.example.com:65536"]]
    (is (thrown? clojure.lang.ExceptionInfo (server/validate-origin! {:public-url url})))))

(deftest database-free-handler-does-not-advertise-oauth
  (let [handler (constantly {:status 404})]
    (is (identical? handler (server/wrap handler nil {} nil)))
    (is (identical? handler (server/wrap-headers handler nil {})))))

(deftest preflight-header-reflection-is-token-checked
  (let [allow #(resource/allow-headers {:headers {"access-control-request-headers" %}})]
    ;; Reflected verbatim so no client library is blocked by a fixed allowlist.
    (is (= "authorization, dpop" (allow "authorization,dpop")))
    (is (= "x-client-added" (allow "  x-client-added  ")))
    (is (= "a, b, c" (allow "a, b,, c")))
    ;; Anything that is not a list of field-name tokens, including control
    ;; characters, a header separator or an oversize value, falls back to the
    ;; advertised default rather than being echoed into the response.
    (doseq [hostile ["evil: value" "has space" "" "\r\nX-Injected: 1" "quote\"name"
                     (apply str (repeat 5000 "a"))]]
      (is (= resource/default-allow-headers (allow hostile)) (pr-str hostile)))
    (is (= resource/default-allow-headers (resource/allow-headers {:headers {}})))))
