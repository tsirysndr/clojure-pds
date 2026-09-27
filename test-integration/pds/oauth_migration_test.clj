(ns pds.oauth-migration-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.app :as app]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.oauth-interaction-test :as owner]
            [pds.oauth-resource-test :as resource]
            [pds.oauth-tokens-test :as token]))

(use-fixtures :each fixture/isolated-database)

(defn- set-status! [status]
  (db/transact! fixture/*ds* #(db/execute! % "UPDATE accounts SET status = ? WHERE did = ?" status owner/did)))
(def session-path "/xrpc/com.atproto.server.getSession")

(deftest deactivated-accounts-authorize-migration-scoped-oauth-sessions
  (let [env (token/env) active (resource/mint env "atproto transition:generic")
        handler (app/handler (resource/settings env) fixture/*ds*)
        get! (fn [issued path] (resource/call handler (resource/request env issued :get path)))]
    (is (= 200 (:status (get! active session-path))))
    (set-status! "deactivated")
    ;; Status changes advance the account's OAuth epoch: sessions issued while
    ;; active never survive into the deactivated period.
    (is (= 401 (:status (get! active session-path))))
    (let [issued (resource/mint env "atproto transition:generic")]
      (let [session (get! issued session-path)]
        (is (= 200 (:status session)) "A fresh flow authorizes the deactivated account")
        (is (= "deactivated" (get-in session [:json "status"])))
        (is (false? (get-in session [:json "active"]))))
      (is (= 200 (:status (get! issued "/xrpc/com.atproto.repo.listMissingBlobs"))))
      ;; Account-status permissions await published semantics; the grant itself
      ;; stays valid and the denial is authorization, not authentication.
      (is (= 403 (:status (get! issued "/xrpc/com.atproto.server.checkAccountStatus"))))
      (let [write (resource/call handler (resource/request env issued :post "/xrpc/com.atproto.repo.createRecord") resource/record-body)]
        (is (= 401 (:status write)) "Routes without inactive support still reject the session")
        (is (= "InvalidToken" (get-in write [:json "error"]))))
      (let [rotated (token/issue env (token/refresh-params issued))]
        (is (= 200 (:status (get! rotated session-path))) "Refresh rotation works while deactivated")
        (is (empty? (token/query "SELECT 1 FROM oauth_sessions WHERE revoke_reason IS NOT NULL")))
        ;; Reactivation advances the epoch again, ending migration sessions.
        (set-status! "active")
        (is (= 401 (:status (get! rotated session-path))))
        (is (= "invalid_grant"
               (try (token/issue env (token/refresh-params rotated)) nil
                    (catch clojure.lang.ExceptionInfo e (:oauth-error (ex-data e))))))
        (is (= ["account_changed"]
               (mapv :revoke_reason (token/query "SELECT revoke_reason FROM oauth_sessions WHERE revoke_reason IS NOT NULL"))))))))

(deftest taken-down-accounts-cannot-authorize-or-use-oauth-sessions
  (let [env (token/env) handler (app/handler (resource/settings env) fixture/*ds*)]
    (set-status! "deactivated")
    (let [issued (resource/mint env "atproto transition:generic")]
      (set-status! "taken_down")
      (is (= 401 (:status (resource/call handler (resource/request env issued :get session-path)))))
      (is (= "AuthenticationRequired"
             (try (resource/mint env "atproto transition:generic") nil
                  (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))))))
