(ns pds.handles-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.events :as events]
            [pds.handles :as handles]
            [pds.http :as http]
            [pds.invites-test :as invites-test]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.plc-directory-test :as directory-test]
            [pds.plc-provision-test :as provision-test]
            [pds.plc-test :as plc-test]
            [pds.protocol.codec :as codec]
            [pds.server-api-test :as api])
  (:import [java.net.http HttpClient]))
(use-fixtures :each fixture/isolated-database)
(defn request [account] {:headers {"authorization" (str "Bearer " (:accessJwt account))}})
(defn due! [] (db/transact! fixture/*ds* #(db/execute! % "UPDATE handle_updates SET available_at = now(), lease_until = CASE WHEN lease_until IS NULL THEN NULL ELSE now() - interval '1 second' END")))
(defn current-handle [did] (:handle (first (provision-test/rows "SELECT handle FROM accounts WHERE did = ?" did))))
(defn changes [did] (provision-test/rows "SELECT payload FROM repo_events WHERE did = ? AND event_type = 'identity' ORDER BY seq" did))

(deftest web-handle-update-keeps-original-did-host-and-rechecks-custom-binding
  (let [binding (atom "did:web:alice.example.com")
        settings (assoc (api/settings) :txt-lookup (fn [_] [(str "did=" @binding)]))
        alice (accounts/create! fixture/*ds* settings (provision-test/signup))
        handler (app/handler settings fixture/*ds*) server (http/start! settings handler)
        did (:did alice) token (:accessJwt alice)]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [call #(api/xrpc client (:port server) %1 %2 %3 %4)
              update #(call "POST" "com.atproto.identity.updateHandle" {"handle" %} token)]
          (is (= 401 (:status (call "POST" "com.atproto.identity.updateHandle" {"handle" "alicia.example.com"} nil))))
          (is (= 200 (:status (update "ALICIA.example.com"))))
          (is (= "alicia.example.com" (current-handle did)))
          (is (= did (get-in (call "GET" "com.atproto.server.getSession" nil token) [:body "did"])))
          (let [doc (json/read-str (:body (handler {:request-method :get :uri "/.well-known/did.json" :headers {"host" "alice.example.com"}})))]
            (is (= did (get doc "id")))
            (is (= ["at://alicia.example.com"] (get doc "alsoKnownAs"))))
          (is (= 200 (:status (update "alicia.example.com"))))
          (is (= 2 (count (changes did))) "No-op does not emit an identity event")
          (is (true? (:permanent (first (provision-test/rows "SELECT permanent FROM handle_reservations WHERE handle = 'alice.example.com'")))))
          (is (thrown? Exception (accounts/create! fixture/*ds* settings (assoc (provision-test/signup) "email" "another@example.com"))))
          (doseq [handle [nil "bad" "pds.example.com" "other.invalid"]] (is (= 400 (:status (update handle)))))
          (is (= 200 (:status (update "alice.custom.example.net"))))
          (is (= "alice.custom.example.net" (current-handle did)))
          (is (= ["at://alice.custom.example.net"]
                 (get-in (call "GET" "com.atproto.identity.getRecommendedDidCredentials" nil token) [:body "alsoKnownAs"])))
          (is (not (contains? (:body (call "GET" "com.atproto.identity.getRecommendedDidCredentials" nil token)) "rotationKeys")))
          (reset! binding "did:web:someone.example.net")
          (is (= "handle.invalid" (get-in (call "GET" (str "com.atproto.identity.resolveIdentity?identifier=" did) nil nil) [:body "handle"])))
          (is (= 400 (:status (update "wrong.example.net"))))
          (is (= "alice.custom.example.net" (current-handle did)))))
      (finally ((:stop! server))))))

(deftest administrative-handle-update-uses-the-owner-flow
  (let [settings (assoc (api/settings) :admin-password invites-test/admin-password)
        alice (accounts/create! fixture/*ds* settings (provision-test/signup))
        did (:did alice)
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [admin #(invites-test/admin-call client (:port server) "POST" "com.atproto.admin.updateAccountHandle" %)]
          (is (= 401 (:status (api/xrpc client (:port server) "POST" "com.atproto.admin.updateAccountHandle"
                                        {"did" did "handle" "alicia.example.com"} (:accessJwt alice)))))
          (is (= 400 (:status (admin {"did" "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa" "handle" "ghost.example.com"}))))
          (is (= 400 (:status (admin {"did" did "handle" "bad handle"}))))
          (is (= 200 (:status (admin {"did" did "handle" "ALICIA.example.com"}))))
          (is (= "alicia.example.com" (current-handle did)))
          (is (= 2 (count (changes did))) "Signup and the admin change each emit one identity event")
          (is (= 200 (:status (api/xrpc client (:port server) "POST" "com.atproto.server.deactivateAccount" {} (:accessJwt alice)))))
          (is (= 200 (:status (admin {"did" did "handle" "renamed.example.com"}))))
          (is (= "renamed.example.com" (current-handle did)))))
      (finally ((:stop! server))))))

(deftest revoked-session-during-custom-domain-check-cannot-change-handle
  (let [base (api/settings) alice (accounts/create! fixture/*ds* base (provision-test/signup))
        settings (assoc base :txt-lookup
                        (fn [_]
                          (db/transact! fixture/*ds* #(db/execute! % "UPDATE sessions SET revoked = true WHERE did = ?" (:did alice)))
                          [(str "did=" (:did alice))]))]
    (is (thrown? Exception (handles/update! fixture/*ds* settings (request alice) {"handle" "alice.example.net"})))
    (is (= "alice.example.com" (current-handle (:did alice))))
    (is (= 1 (count (changes (:did alice)))))
    (is (empty? (provision-test/rows "SELECT * FROM handle_reservations WHERE handle = 'alice.example.net'")))))

(deftest plc-update-is-durable-and-reserves-handle-against-signup
  (directory-test/with-directory
    (fn [{:keys [client origin mode calls]}]
      (let [settings (provision-test/settings client origin)
            alice (accounts/create! fixture/*ds* settings (provision-test/signup))
            did (:did alice) server (http/start! settings (app/handler settings fixture/*ds*))]
        (try
          (with-open [http (HttpClient/newHttpClient)]
            (let [call #(api/xrpc http (:port server) %1 %2 %3 (:accessJwt alice))
                  update #(call "POST" "com.atproto.identity.updateHandle" {"handle" %})]
              (let [recommended (:body (call "GET" "com.atproto.identity.getRecommendedDidCredentials" nil))
                    op (codec/decode (:operation (first (provision-test/rows "SELECT operation FROM plc_identities WHERE did = ?" did))))]
                (is (= (get op "rotationKeys") (get recommended "rotationKeys")))
                (is (= (get op "verificationMethods") (get recommended "verificationMethods")))
                (is (= (get op "services") (get recommended "services"))))
              (reset! mode :ignore)
              (is (= 503 (:status (update "alicia.example.com"))))
              (is (= "alice.example.com" (current-handle did)))
              (is (= 1 (count (changes did))))
              (is (= 409 (:status (update "different.example.com"))))
              (is (thrown? Exception (accounts/create! fixture/*ds* settings (assoc (provision-test/signup) "handle" "alicia.example.com" "email" "another@example.com"))))
              (is (= 1 (provision-test/scalar "SELECT count(*) AS n FROM accounts")))
              (let [op (:operation (first (provision-test/rows "SELECT operation FROM handle_updates")))]
                (due!) (reset! mode :accept)
                (is (= :updated (handles/process-one! fixture/*ds* settings nil)))
                (is (= (vec op) (vec (:operation (first (provision-test/rows "SELECT operation FROM plc_identities WHERE did = ?" did)))))))
              (is (= "alicia.example.com" (current-handle did)))
              (is (empty? (provision-test/rows "SELECT * FROM handle_updates")))
              (is (= 2 (count (changes did))))
              (is (empty? (provision-test/rows "SELECT * FROM handle_reservations WHERE handle = 'alice.example.com'")))
              (is (= ["at://alicia.example.com"]
                     (get-in (call "GET" (str "com.atproto.identity.resolveDid?did=" did) nil) [:body "didDoc" "alsoKnownAs"])))
              (is (= 200 (:status (update "alicia.example.com"))))
              (is (= 3 (count (directory-test/posts calls))) "Genesis, unconfirmed attempt, then confirmed retry")
              (is (string? (:did (accounts/create! fixture/*ds* settings (assoc (provision-test/signup) "email" "another@example.com"))))
                  "Released PLC handle can be assigned to another account")))
          (finally ((:stop! server))))))))

(deftest plc-handle-retry-reconciles-accepted-operation-after-local-failure
  (directory-test/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision-test/settings client origin)
            alice (accounts/create! fixture/*ds* settings (provision-test/signup)) did (:did alice)]
        (with-redefs [events/append! (fn [& _] (throw (ex-info "Simulated event storage failure" {})))]
          (is (thrown? Exception (handles/update! fixture/*ds* settings (request alice) {"handle" "alicia.example.com"}))))
        (is (= "alice.example.com" (current-handle did)))
        (is (= 2 (count (directory-test/posts calls))))
        (due!)
        (is (nil? (handles/update! fixture/*ds* settings (request alice) {"handle" "alicia.example.com"})))
        (is (= 2 (count (directory-test/posts calls))))
        (is (= "alicia.example.com" (current-handle did)))
        (is (= 2 (count (changes did))))))))

(deftest plc-updates-preserve-fresh-directory-fields-and-reject-key-drift
  (directory-test/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision-test/settings client origin)
            alice (accounts/create! fixture/*ds* settings (provision-test/signup)) did (:did alice)
            stored (first (provision-test/rows "SELECT * FROM plc_identities WHERE did = ?" did))
            signer {:algorithm "ES256K" :private (crypto/unseal (:master-key settings) (str did ":plc-rotation") (:rotation_key stored))}
            genesis (codec/decode (:operation stored))
            external (plc-test/update-op genesis signer {"alsoKnownAs" ["at://alice.example.com" "https://profile.example.net"]
                                                         "services" (assoc (get genesis "services") "extra" {"type" "Example" "endpoint" "https://extra.example.net"})})]
        (directory/ensure-operation! client origin did external)
        (handles/update! fixture/*ds* settings (request alice) {"handle" "alicia.example.com"})
        (let [updated (codec/decode (:operation (first (provision-test/rows "SELECT operation FROM plc_identities WHERE did = ?" did))))
              drifted (plc-test/update-op updated signer {"verificationMethods" {"atproto" (plc/did-key (crypto/keypair "ES256"))}})]
          (is (= ["at://alicia.example.com" "https://profile.example.net"] (get updated "alsoKnownAs")))
          (is (= (get external "services") (get updated "services")))
          (is (= (plc/operation-cid external) (get updated "prev")))
          (directory/ensure-operation! client origin did drifted)
          (is (= "IdentityMismatch" (:error (try (handles/update! fixture/*ds* settings (request alice) {"handle" "changed.example.com"}) nil
                                                 (catch clojure.lang.ExceptionInfo e (ex-data e))))))
          (is (= 4 (count (directory-test/posts calls))))
          (is (= "alicia.example.com" (current-handle did)))
          (is (empty? (provision-test/rows "SELECT * FROM handle_updates"))))))))

(deftest handle-reservations-backfill-existing-accounts
  ;; Replays a prefix of the PostgreSQL migration chain; the SQLite backend
  ;; ships one consolidated baseline with no partial history.
  (when (fixture/postgres?)
  (let [migrations db/migrations]
    (with-redefs [db/migrations (vec (take-while #(not= "015-handle-updates.sql" %) migrations))]
      (fixture/isolated-database
        (fn []
          (db/transact! fixture/*ds* #(db/execute! % "INSERT INTO accounts(did, handle, email, password_hash) VALUES
                            ('did:web:original.example.com', 'current.example.com', 'old@example.com', 'unused')"))
          (with-redefs [db/migrations migrations] (db/migrate! fixture/*ds*))
          (is (= [{:handle "current.example.com" :permanent false} {:handle "original.example.com" :permanent true}]
                 (provision-test/rows "SELECT handle, permanent FROM handle_reservations ORDER BY handle")))))))))

(deftest expired-handle-lease-cannot-overwrite-a-later-update
  (directory-test/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (provision-test/settings client origin)
            alice (accounts/create! fixture/*ds* settings (provision-test/signup)) did (:did alice)
            entered (promise) release (promise) attempts (atom 0) ensure! directory/ensure-operation!]
        (with-redefs [directory/ensure-operation!
                      (fn [& args]
                        (let [result (apply ensure! args)]
                          (when (= 1 (swap! attempts inc)) (deliver entered true) @release)
                          result))]
          (let [updating (future (try (handles/update! fixture/*ds* settings (request alice) {"handle" "alicia.example.com"})
                                      (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))]
            (try
              (is (= true (deref entered 10000 :timeout)))
              (is (nil? (handles/process-one! fixture/*ds* settings nil)))
              (due!)
              (is (= :updated (handles/process-one! fixture/*ds* settings nil)))
              (handles/update! fixture/*ds* settings (request alice) {"handle" "latest.example.com"})
              (finally (deliver release true)))
            (is (= "IdentityUpdatePending" (deref updating 15000 :timeout)))
            (is (= "latest.example.com" (current-handle did)))
            (is (= 3 (count (changes did))))
            (is (= 3 (count (directory-test/posts calls))))))))))

(deftest custom-plc-proof-is-rechecked-before-submit-but-not-after-acceptance
  (directory-test/with-directory
    (fn [{:keys [client origin calls]}]
      (let [base (provision-test/settings client origin)
            alice (accounts/create! fixture/*ds* base (provision-test/signup)) did (:did alice)
            proof (atom did) lookups (atom 0)
            settings (assoc base :txt-lookup (fn [_]
                                               ;; Control was lost after initial validation.
                                               (when (= 2 (swap! lookups inc)) (reset! proof "did:web:someone.example.net"))
                                               [(str "did=" @proof)]))]
        (is (= "IdentityUpdatePending"
               (:error (try (handles/update! fixture/*ds* settings (request alice) {"handle" "custom.example.net"}) nil
                            (catch clojure.lang.ExceptionInfo e (ex-data e))))))
        (is (= 1 (count (directory-test/posts calls))) "No submission with an invalid custom binding")
        (is (= "alice.example.com" (current-handle did)))
        (reset! proof did)
        (due!)
        (with-redefs [events/append! (fn [& _] (throw (ex-info "Local commit failed after directory acceptance" {})))]
          (is (thrown? Exception (handles/process-one! fixture/*ds* settings nil))))
        (is (= 2 (count (directory-test/posts calls))))
        (reset! proof "did:web:someone.example.net")
        (due!)
        (is (= :updated (handles/process-one! fixture/*ds* settings nil)))
        (is (= 2 (count (directory-test/posts calls))) "Already accepted operation is reconciled without resubmission")
        (is (= "custom.example.net" (current-handle did)))
        (is (= 3 @lookups) "Reconciliation must not require a new DNS proof")))))
