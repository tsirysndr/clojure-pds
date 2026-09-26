(ns pds.oauth-par-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.oauth.client :as client]
            [pds.oauth.client-auth-test :as auth]
            [pds.oauth.client-test :as metadata]
            [pds.oauth.dpop :as dpop]
            [pds.oauth.dpop-test :as proof]
            [pds.oauth.par :as par]
            [pds.oauth.par-test :as par-test]
            [pds.protocol.codec :as codec]
            [pds.proxy-api-test :as wire])
  (:import [java.net URLEncoder]
           [java.net.http HttpClient]
           [java.time Instant]))

(use-fixtures :each fixture/isolated-database)
(defn resolver [] (client/resolver {:oauth-client-fetch (fn [_ _] (metadata/response (metadata/metadata)))}))
(defn dpop-proof [key] (proof/sign key (assoc (proof/claims) "htu" (str (:public-url proof/settings) "/oauth/par"))))
(defn push [resolver params key] (par/push! fixture/*ds* resolver proof/settings params (dpop-proof key)))
(defn claim [client-id request-uri] (db/transact! fixture/*ds* #(par/claim! % client-id request-uri)))
(defn rows [sql] (with-open [conn (db/connection fixture/*ds*)] (db/query conn sql)))
(defn scalar [sql] (:n (first (rows sql))))
(defn form [params] (codec/utf8 (str/join "&" (map (fn [[k v]] (str (URLEncoder/encode k "UTF-8") "=" (URLEncoder/encode v "UTF-8"))) params))))

(deftest pushed-state-is-durable-private-and-consumed-once
  (with-redefs [dpop/now (constantly proof/timestamp)]
    (let [resolver (resolver) key (crypto/keypair) params (par-test/params)
          result (push resolver params key) uri (:request_uri result)]
      (is (= 90 (:expires_in result)))
      (is (str/starts-with? uri par/request-uri-prefix))
      (is (= [(crypto/digest-token uri)] (mapv :request_hash (rows "SELECT request_hash FROM oauth_par_requests"))))
      (is (= "invalid_request_uri" (proof/error #(claim "https://wrong.example.com" uri))))
      (is (thrown? Exception
            (db/transact! fixture/*ds* (fn [conn]
                                       (par/claim! conn metadata/client-id uri)
                                       (throw (ex-info "Failed to create browser interaction" {}))))))
      (let [reopened (db/datasource {:url (.getURL fixture/*ds*) :user (.getUser fixture/*ds*) :password (.getPassword fixture/*ds*)})
            gate (promise) tasks (mapv (fn [_] (future @gate
                                                (try (db/transact! reopened #(par/claim! % metadata/client-id uri))
                                                     (catch clojure.lang.ExceptionInfo e (:oauth-error (ex-data e)))))) (range 12))]
        (deliver gate true)
        (let [results (mapv #(deref % 15000 :timeout) tasks) snapshot (first (filter map? results))]
          (is (= 1 (count (filter map? results))))
          (is (= 11 (count (filter #{"invalid_request_uri"} results))))
          (is (= (dissoc params "client_id") (:parameters snapshot)))
          (is (= {:client-id metadata/client-id :method "none"} (:client-binding snapshot)))
          (is (= (:jkt (dpop/verify! proof/settings (dpop-proof key) {:method "POST" :url "https://pds.example.com/oauth/par"})) (:dpop-jkt snapshot)))))
      (is (= "invalid_request_uri" (proof/error #(claim metadata/client-id uri))))
      (is (= 1 (scalar "SELECT count(*) AS n FROM oauth_pkce_uses"))))))

(deftest challenges-are-unique-across-concurrent-submissions-and-retained-for-24-hours
  (with-redefs [dpop/now (constantly proof/timestamp)]
    (let [resolver (resolver) key (crypto/keypair) params (par-test/params)
          gate (promise) tasks (mapv (fn [_] (future @gate
                                              (try (push resolver params key)
                                                   (catch clojure.lang.ExceptionInfo e (:oauth-error (ex-data e)))))) (range 8))]
      (deliver gate true)
      (let [results (mapv #(deref % 15000 :timeout) tasks)]
        (is (= 1 (count (filter map? results))))
        (is (= 7 (count (filter #{"invalid_request"} results)))))
      (is (= 1 (scalar "SELECT count(*) AS n FROM oauth_par_requests")))
      (is (= 1 (scalar "SELECT count(*) AS n FROM oauth_pkce_uses")))
      (with-redefs [dpop/now (constantly (+ proof/timestamp 86399))]
        (is (= "invalid_request" (proof/error #(push resolver params key))))
        (push resolver (par-test/params) key)
        (is (= 1 (scalar "SELECT count(*) AS n FROM oauth_par_requests")))
        (is (= 2 (scalar "SELECT count(*) AS n FROM oauth_pkce_uses"))))
      (with-redefs [dpop/now (constantly (+ proof/timestamp 86400))]
        (is (map? (push resolver params key)))
        (is (= 2 (scalar "SELECT count(*) AS n FROM oauth_pkce_uses")))))))

(deftest expiry-proof-binding-and-transaction-boundaries
  (with-redefs [dpop/now (constantly proof/timestamp)]
    (let [resolver (resolver) key (crypto/keypair) params (par-test/params)
          uri (:request_uri (push resolver params key))]
      (with-redefs [dpop/now (constantly (+ proof/timestamp 90))]
        (is (= "invalid_request_uri" (proof/error #(claim metadata/client-id uri)))))
      (doseq [bad [nil "" "https://external.example.com/request" (str uri "extra")]]
        (is (= "invalid_request_uri" (proof/error #(claim metadata/client-id bad)))))
      (is (= "invalid_dpop_proof" (proof/error #(push resolver (assoc (par-test/params) "dpop_jkt" (crypto/token)) key))))
      (is (= 1 (scalar "SELECT count(*) AS n FROM oauth_par_requests")))
      (is (= 1 (scalar "SELECT count(*) AS n FROM oauth_pkce_uses")))
      (with-open [conn (db/connection fixture/*ds*)]
        (is (thrown? Exception (par/claim! conn metadata/client-id uri)))))))

(deftest standalone-par-http-adapter-obeys-oauth-errors-cors-and-nonce-retries
  (metadata/with-server
    (fn [{:keys [resolver responses]}]
      (let [client-key (auth/new-key) dpop-key (crypto/keypair)
            _ (swap! responses assoc "/client.json" (metadata/response (auth/document [client-key])))
            server (http/start! {:host "127.0.0.1" :port 0} (par/handler fixture/*ds* proof/settings resolver))
            port (:port server) params (merge (par-test/params) (auth/params client-key))
            call (fn [http-client method headers body]
                   (wire/call http-client port method "/oauth/par" nil headers body))]
        (try
          (with-open [http-client (HttpClient/newHttpClient)]
            (let [missing (call http-client "POST" {"Content-Type" "application/x-www-form-urlencoded"} (form params))]
              (is (= 400 (:status missing)))
              (is (= "invalid_dpop_proof" (get-in missing [:body "error"])))
              (is (string? (get-in missing [:headers "dpop-nonce"]))))
            (let [without-nonce (proof/sign dpop-key (-> (proof/claims) (assoc "htu" "https://pds.example.com/oauth/par") (dissoc "nonce")))
                  challenge (call http-client "POST" {"Content-Type" "application/x-www-form-urlencoded" "DPoP" without-nonce} (form params))
                  nonce (get-in challenge [:headers "dpop-nonce"])
                  token (proof/sign dpop-key (assoc (proof/claims) "htu" "https://pds.example.com/oauth/par" "nonce" nonce))
                  accepted (call http-client "POST" {"Content-Type" "application/x-www-form-urlencoded" "DPoP" token} (form params))]
              (is (= "use_dpop_nonce" (get-in challenge [:body "error"])))
              (is (= 201 (:status accepted)))
              (is (= 90 (get-in accepted [:body "expires_in"])))
              (is (= "*" (get-in accepted [:headers "access-control-allow-origin"])))
              (is (= "DPoP-Nonce" (get-in accepted [:headers "access-control-expose-headers"])))
              (is (= "no-store" (get-in accepted [:headers "cache-control"])))
              (is (= 1 (scalar "SELECT count(*) AS n FROM oauth_client_assertion_uses")))
              (let [snapshot (claim metadata/client-id (get-in accepted [:body "request_uri"]))]
                (is (= (:kid client-key) (get-in snapshot [:client-binding :kid])))
                (is (not (contains? (:parameters snapshot) "client_assertion")))))
            (doseq [[method headers body status] [["OPTIONS" {} nil 204] ["GET" {} nil 405]
                                                 ["POST" {"Content-Type" "application/json"} (codec/utf8 "{}") 400]
                                                 ["POST" {"Content-Type" "application/x-www-form-urlencoded"} (codec/utf8 "client_id=a&client_id=b") 400]
                                                 ["POST" {"Content-Type" "application/x-www-form-urlencoded"} (byte-array 16385) 413]
                                                 ["POST" {"Content-Type" "application/x-www-form-urlencoded" "Authorization" "Basic ignored"} (form params) 400]]]
              (let [response (call http-client method headers body)]
                (is (= status (:status response)))
                (is (string? (get-in response [:headers "dpop-nonce"]))))))
          (finally ((:stop! server))))))))

(deftest challenge-reservation-is-global-and-rolls-back-with-a-failed-insert
  (with-redefs [dpop/now (constantly proof/timestamp)]
    (let [resolver (resolver) key (crypto/keypair) params (assoc (par-test/params) "state" "reject-in-test")]
      (db/transact! fixture/*ds* #(db/execute! % "ALTER TABLE oauth_par_requests ADD CONSTRAINT test_insert_failure CHECK (parameters->>'state' <> 'reject-in-test')"))
      (is (thrown? java.sql.SQLException (push resolver params key)))
      (is (= 0 (scalar "SELECT count(*) AS n FROM oauth_pkce_uses")))
      (is (= 0 (scalar "SELECT count(*) AS n FROM oauth_par_requests")))
      (push resolver (assoc params "state" "allowed") key)
      (let [other-id "https://another.example.com/client.json"
            other (client/resolver {:oauth-client-fetch (fn [_ _] (metadata/response (assoc (metadata/metadata) "client_id" other-id)))})]
        (is (= "invalid_request"
               (proof/error #(push other (assoc params "state" "allowed" "client_id" other-id) key)))))
      (is (= 1 (scalar "SELECT count(*) AS n FROM oauth_pkce_uses")))
      (is (= 1 (scalar "SELECT count(*) AS n FROM oauth_par_requests"))))))

(deftest expired-request-and-challenge-cleanup-is-bounded
  (with-redefs [dpop/now (constantly proof/timestamp)]
    (let [now (Instant/ofEpochSecond proof/timestamp)]
      (db/transact! fixture/*ds*
        (fn [conn]
          (db/execute! conn "INSERT INTO oauth_pkce_uses SELECT lpad(n::text, 43, '0'), ? FROM generate_series(1, 1005) n" (.minusSeconds now 1))
          (db/execute! conn "INSERT INTO oauth_par_requests(request_hash, client_id, parameters, client_binding, dpop_jkt, created_at, expires_at)
                             SELECT lpad(n::text, 43, '0'), ?, '{}'::jsonb, '{}'::jsonb, ?, ?, ? FROM generate_series(1, 1005) n"
                       metadata/client-id (crypto/token) (.minusSeconds now 10) (.minusSeconds now 1))))
      (push (resolver) (par-test/params) (crypto/keypair))
      (is (= 6 (scalar "SELECT count(*) AS n FROM oauth_pkce_uses")))
      (is (= 6 (scalar "SELECT count(*) AS n FROM oauth_par_requests"))))))
