(ns pds.email-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [pds.email :as email]
            [pds.http :as http])
  (:import [java.util UUID]))

(deftest email-configuration
  (is (nil? (email/settings {})))
  (is (= "pds@example.com"
         (:from (email/settings {"PDS_EMAIL_WORKER_URL" "https://mail.example.com/send"
                                 "PDS_EMAIL_WORKER_TOKEN" "test-secret"
                                 "PDS_EMAIL_FROM" "pds@example.com"}))))
  (doseq [overrides [{"PDS_EMAIL_WORKER_URL" "http://example.com/send"}
                    {"PDS_EMAIL_WORKER_URL" "https://user:pass@example.com/send"}
                    {"PDS_EMAIL_WORKER_TOKEN" ""}
                    {"PDS_EMAIL_FROM" "bad\r\n@example.com"}]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (email/settings (merge {"PDS_EMAIL_WORKER_URL" "https://mail.example.com/send"
                                         "PDS_EMAIL_WORKER_TOKEN" "test-secret"
                                         "PDS_EMAIL_FROM" "pds@example.com"} overrides))))))

(deftest worker-http-contract
  (let [seen (atom nil)
        status (atom 202)
        server (http/start! {:host "127.0.0.1" :port 0}
                            (fn [req]
                              (reset! seen (assoc (select-keys req [:headers :request-method])
                                                 :body (json/read-str (slurp (:body req)))))
                              {:status @status :body ""}))
        sender (email/worker-sender {:url (str "http://127.0.0.1:" (:port server) "/send")
                                     :token "test-secret" :from "pds@example.com"})
        id (UUID/randomUUID)
        message {:id id :payload {:to "user@example.com" :subject "Confirm" :text "test message"}}]
    (try
      (is (nil? ((:send! sender) message)))
      (is (= :post (:request-method @seen)))
      (is (= "Bearer test-secret" (get-in @seen [:headers "authorization"])))
      (is (= (str id) (get-in @seen [:headers "idempotency-key"])))
      (is (= {"to" "user@example.com" "from" "pds@example.com" "subject" "Confirm"
              "text" "test message" "idempotencyKey" (str id)} (:body @seen)))
      (doseq [[code retry?] [[429 true] [503 true] [400 false] [401 false] [302 false]]]
        (reset! status code)
        (try ((:send! sender) message) (is false "Expected failure")
             (catch clojure.lang.ExceptionInfo e
               (is (= retry? (:retryable (ex-data e)))))))
      (finally ((:close! sender)) ((:stop! server))))))
