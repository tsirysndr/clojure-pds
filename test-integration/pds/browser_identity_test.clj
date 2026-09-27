(ns pds.browser-identity-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.browser-security-test :as browser-test]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.plc-directory-test :as tls]
            [pds.plc-keys-test :as ct]
            [pds.plc-provision-test :as provision]
            [pds.plc-recovery-keys-test :as recovery-test]
            [pds.security.browser :as browser]
            [pds.security.identity :as identity]
            [pds.security.web :as web]
            [pds.server-api-test :as api])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))

(use-fixtures :each fixture/isolated-database)
(defn login [settings did]
  (browser-test/act settings (browser-test/open) "login/password" {"identifier" did "password" "signup-password"}))
(defn change [settings state body]
  (identity/change! fixture/*ds* settings (:token state) (get-in state [:view :csrf]) body))
(defn code [settings state]
  (browser-test/act settings state "identity/recovery/email" {})
  (api/email-token "Authorize a PLC identity operation"))
(defn tokens [did]
  (provision/rows "SELECT * FROM account_tokens WHERE did = ? AND purpose = 'plc-operation'" did))
(defn new-key [] (plc/did-key (crypto/keypair "ES256")))
(defn tx [f] (db/transact! fixture/*ds* f))

(deftest owner-recovery-changes-are-email-authorized-and-retryable-after-reload
  (tls/with-directory
    (fn [{:keys [client origin mode]}]
      (let [settings (provision/settings client origin) alice (accounts/create! fixture/*ds* settings (provision/signup))
            did (:did alice) state (login settings did) expected (recovery-test/cid did) keys [(new-key)]
            token (code settings state) body {"previousCid" expected "recoveryKeys" keys "code" token}]
        (is (= [] (get-in state [:view :recovery-keys :recoveryKeys])))
        (is (= "InvalidToken" (ct/error #(change settings state (assoc body "code" "wrong")))))
        (is (= 1 (count (tokens did))))
        (reset! mode :ignore)
        (let [pending (change settings state body) op (:operation (ct/job did))
              reopened (browser/open! fixture/*ds* (:token state))]
          (is (= "pending" (get-in pending [:result :state])))
          (is (= keys (get-in reopened [:view :recovery-keys :pending :recoveryKeys])))
          (is (= expected (get-in reopened [:view :recovery-keys :pending :previousCid])))
          (is (empty? (tokens did)))
          (is (= "IdentityMismatch" (ct/error #(change settings state (assoc body "recoveryKeys" [])))))
          (reset! mode :accept-error)
          (let [completed (change settings reopened (dissoc body "code"))]
            (is (= "completed" (get-in completed [:result :state])))
            (is (= keys (get-in completed [:view :recovery-keys :recoveryKeys])))
            (is (nil? (get-in completed [:view :recovery-keys :pending])))
            (is (= (vec op) (vec (:operation (ct/stored did)))))
            (is (= (:result completed) (:result (change settings state (dissoc body "code")))))))
        (is (= "InvalidToken" (ct/error #(change settings state (assoc body "previousCid" (recovery-test/cid did) "recoveryKeys" [])))))
        (is (= 1 (count (recovery-test/changes))))))))

(deftest rejected-and-noop-changes-preserve-email-proof
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) did (:did (accounts/create! fixture/*ds* settings (provision/signup)))
            state (login settings did) body {"previousCid" (recovery-test/cid did) "code" (code settings state) "recoveryKeys" []}]
        (is (= "unchanged" (get-in (change settings state body) [:result :state])))
        (is (= 1 (count (tokens did))))
        (is (= "InvalidRequest" (ct/error #(change settings state (assoc body "recoveryKeys" ["private-key"])))))
        (is (= 1 (count (tokens did))))
        (let [execute! db/execute!]
          (with-redefs [db/execute! (fn [conn sql & args]
                                     (if (str/starts-with? sql "INSERT INTO handle_updates")
                                       (throw (ex-info "Queue unavailable" {}))
                                       (apply execute! conn sql args)))]
            (is (thrown-with-msg? Exception #"Queue unavailable" (change settings state (assoc body "recoveryKeys" [(new-key)]))))))
        (is (= 1 (count (tokens did))) "Consumption rolls back with insertion")
        (is (nil? (ct/job did)))
        (is (= "completed" (get-in (change settings state (assoc body "recoveryKeys" [(new-key)])) [:result :state])))))))

(deftest browser-authorization-is-rechecked-after-unlocked-directory-io
  (tls/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision/settings client origin)]
        (doseq [mutation [:logout :expiry :epoch :email-proof]]
          (let [name (name mutation) did (:did (accounts/create! fixture/*ds* settings
                                                 (assoc (provision/signup) "handle" (str name ".example.com") "email" (str name "@example.com"))))
                state (login settings did) expected (recovery-test/cid did)
                body {"previousCid" expected "code" (code settings state) "recoveryKeys" [(new-key)]}
                audit! directory/audit! posts-before (count (tls/posts calls))]
            (with-redefs [directory/audit!
                          (fn [& args]
                            (let [result (apply audit! args)
                                  ;; A separate connection must acquire browser/account locks
                                  ;; during I/O. A leaked transaction would time out this mutation.
                                  done (future
                                         (case mutation
                                           :logout (browser-test/act settings state "logout" {})
                                           :expiry (tx #(db/execute! % "UPDATE browser_sessions SET created_at = now() - interval '6 minutes', expires_at = now() - interval '1 second' WHERE did = ?" did))
                                           :epoch (tx #(db/execute! % "UPDATE accounts SET oauth_epoch = oauth_epoch + 1 WHERE did = ?" did))
                                           :email-proof (tx #(db/execute! % "DELETE FROM account_tokens WHERE did = ? AND purpose = 'plc-operation'" did))))]
                              (is (not= ::timeout (deref done 5000 ::timeout)))
                              result))]
              (is (= (if (= mutation :email-proof) "InvalidToken" "BrowserSessionRequired")
                     (ct/error #(change settings state body)))))
            (is (= posts-before (count (tls/posts calls))))
            (is (= expected (recovery-test/cid did)))
            (is (nil? (ct/job did)))
            (when-not (= mutation :email-proof) (is (= 1 (count (tokens did)))))))))))

(deftest logout-after-acceptance-does-not-cancel-authorized-work
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) did (:did (accounts/create! fixture/*ds* settings (provision/signup)))
            state (login settings did) keys [(new-key)]
            body {"previousCid" (recovery-test/cid did) "code" (code settings state) "recoveryKeys" keys}
            ensure! directory/ensure-operation!]
        (with-redefs [directory/ensure-operation!
                      (fn [& args]
                        (browser-test/act settings state "logout" {})
                        (apply ensure! args))]
          (is (= "BrowserSessionRequired" (ct/error #(change settings state body)))))
        (is (empty? (tokens did)))
        (let [fresh (login settings did)]
          (is (= keys (get-in fresh [:view :recovery-keys :recoveryKeys])))
          (is (= "completed" (get-in (change settings fresh (dissoc body "code")) [:result :state]))))))))

(defn request [client port settings state action body overrides]
  (let [builder (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/account/action/" action)))
                    (.timeout (Duration/ofSeconds 20)))
        headers (merge {"Origin" (:public-url settings) "Content-Type" "application/json"
                        "Cookie" (str (web/cookie-name settings) "=" (:token state))
                        "X-CSRF-Token" (get-in state [:view :csrf])} overrides)]
    (doseq [[k v] headers :when v] (.header builder k v))
    (let [response (.send ^HttpClient client (-> builder (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body))) .build)
                          (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode response) :body (json/read-str (.body response))})))

(deftest http-recovery-settings-require-same-origin-csrf-and-complete-owner-login
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) did (:did (accounts/create! fixture/*ds* settings (provision/signup)))
            other (:did (accounts/create! fixture/*ds* settings (assoc (provision/signup) "handle" "bob.example.com" "email" "bob@example.com")))
            state (login settings did) anonymous (browser-test/open)
            body {"did" other "previousCid" (recovery-test/cid did) "code" (code settings state) "recoveryKeys" [(new-key)]}
            server (http/start! settings (web/handler fixture/*ds* settings))]
        (try
          (with-open [http (HttpClient/newHttpClient)]
            (doseq [[owner overrides status] [[state {"Origin" "https://evil.example"} 403]
                                              [state {"X-CSRF-Token" (get-in anonymous [:view :csrf])} 403]
                                              [state {"Sec-Fetch-Site" "cross-site"} 403]
                                              [anonymous {} 401]
                                              [state {"Cookie" nil} 401]]]
              (is (= status (:status (request http (:port server) settings owner "identity/recovery/change" body overrides)))))
            (is (= 1 (count (tokens did))))
            (let [result (request http (:port server) settings state "identity/recovery/change" body {})]
              (is (= 200 (:status result)))
              (is (= "completed" (get-in result [:body "result" "state"]))))
            (is (= [] (get-in (login settings other) [:view :recovery-keys :recoveryKeys])) "Submitted DID cannot choose another account")
            (tx #(db/execute! % "UPDATE accounts SET email_auth_factor = true, email_confirmed = true WHERE did = ?" did))
            (let [partial (login settings did)]
              (is (= "factor" (get-in partial [:view :stage])))
              (is (nil? (get-in partial [:view :recovery-keys])))
              (is (= 401 (:status (request http (:port server) settings partial "identity/recovery/change" body {}))))
              (is (= 401 (:status (request http (:port server) settings partial "identity/recovery/email" {} {}))))))
          (finally ((:stop! server))))))))

(deftest unsupported-identities-and-disabled-email-do-not-queue-changes
  (let [settings (api/settings) did (:did (accounts/create! fixture/*ds* settings (assoc (provision/signup) "handle" "web.example.com" "email" "web@example.com"))) state (login settings did)]
    (is (nil? (get-in state [:view :recovery-keys])))
    (is (= "UnsupportedDID" (ct/error #(browser-test/act settings state "identity/recovery/email" {})))))
  (tls/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin) did (:did (accounts/create! fixture/*ds* settings (provision/signup))) state (login settings did)
            body {"previousCid" (recovery-test/cid did) "code" (code settings state) "recoveryKeys" [(new-key)]}
            disabled (assoc settings :email-enabled false)]
        (is (= "EmailUnavailable" (ct/error #(browser-test/act disabled state "identity/recovery/email" {}))))
        (is (= "EmailUnavailable" (ct/error #(change disabled state body))))
        (is (= 1 (count (tokens did))))
        (is (nil? (ct/job did)))))))

(deftest concurrent-owner-retries-consume-one-proof-for-one-durable-intent
  (tls/with-directory
    (fn [{:keys [client origin mode]}]
      (let [settings (provision/settings client origin) did (:did (accounts/create! fixture/*ds* settings (provision/signup)))
            sessions [(login settings did) (login settings did)]
            body {"previousCid" (recovery-test/cid did) "code" (code settings (first sessions)) "recoveryKeys" [(new-key)]}
            start (promise)]
        (reset! mode :ignore)
        (let [jobs (mapv (fn [state] (future @start (change settings state body))) sessions)]
          (deliver start true)
          (let [results (mapv #(deref % 20000 ::timeout) jobs)]
            (is (not-any? #{::timeout} results))
            (is (= 1 (count (set (map #(get-in % [:result :operationCid]) results)))))))
        (is (empty? (tokens did)))
        (is (= 1 (count (provision/rows "SELECT * FROM handle_updates WHERE did = ?" did))))
        (reset! mode :accept)
        (is (= "completed" (get-in (change settings (first sessions) (dissoc body "code")) [:result :state])))
        (is (= 1 (count (recovery-test/changes))))))))
