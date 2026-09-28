(ns pds.oauth-cleanup-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.oauth-interaction-test :as owner]
            [pds.oauth-par-test :as par]
            [pds.oauth-tokens-test :as token]
            [pds.oauth.cleanup :as cleanup]
            [pds.oauth.dpop :as dpop]
            [pds.oauth.dpop-test :as proof]))

(use-fixtures :each fixture/isolated-database)
(def empty-sweep {:tokens 0 :sessions 0 :codes 0})
(defn expire! []
  (owner/mutate "UPDATE oauth_sessions SET created_at = now() - interval '2 days', expires_at = now() - interval '1 day'")
  (owner/mutate "UPDATE oauth_codes SET created_at = now() - interval '2 days', expires_at = now() - interval '1 day'"))

(deftest live-families-retain-expired-codes-and-rotated-tokens-for-replay
  (let [env (token/env) code (token/approved env) issued (token/issue env code)
        rotated (token/issue env (token/refresh-params issued))]
    (with-redefs [dpop/now (constantly (+ 600 (dpop/now)))]
      (is (= empty-sweep (cleanup/collect! fixture/*ds*)))
      (is (= 4 (par/scalar "SELECT count(*) AS n FROM oauth_tokens")))
      (is (= 1 (par/scalar "SELECT count(*) AS n FROM oauth_codes"))))
    (is (= "invalid_grant" (proof/error #(token/issue env (token/refresh-params issued)))))
    (is (= "invalid_grant" (proof/error #(token/access rotated))))
    (is (= empty-sweep (cleanup/collect! fixture/*ds*)) "Revocation does not shorten replay retention")
    (let [code (token/approved env) issued (token/issue env code)]
      (with-redefs [dpop/now (constantly (+ 600 (dpop/now)))]
        (is (= empty-sweep (cleanup/collect! fixture/*ds*))))
      (is (= "invalid_grant" (proof/error #(token/issue env code))))
      (is (= "invalid_grant" (proof/error #(token/access issued)))))
    (expire!)
    (is (= {:tokens 6 :sessions 2 :codes 2} (cleanup/collect! fixture/*ds*)))
    (is (= empty-sweep (cleanup/collect! fixture/*ds*)))))

(deftest token-history-is-deleted-in-batches-without-cascading-the-family
  (let [env (token/env) _ (token/issue env (token/approved env))]
    (owner/mutate "INSERT INTO oauth_tokens(token_hash, session_id, kind, scope, created_at, expires_at, used_at)
                   SELECT lpad(n::text, 43, '0'), s.session_id, 'refresh', 'atproto', s.created_at, s.expires_at, s.created_at
                   FROM oauth_sessions s CROSS JOIN generate_series(1, 1005) n")
    (expire!)
    (is (= {:tokens 1000 :sessions 0 :codes 0} (cleanup/collect! fixture/*ds*)))
    (is (= 7 (par/scalar "SELECT count(*) AS n FROM oauth_tokens")))
    (is (= 1 (par/scalar "SELECT count(*) AS n FROM oauth_codes")))
    (is (= {:tokens 7 :sessions 1 :codes 1} (cleanup/collect! fixture/*ds*)))
    (is (= empty-sweep (cleanup/collect! fixture/*ds*)))))

(deftest family-and-unused-code-batches-are-bounded
  (let [env (token/env) _ (token/issue env (token/approved env))]
    (owner/mutate "INSERT INTO oauth_sessions(session_id, code_hash, did, account_epoch, client_id, snapshot, created_at, expires_at)
                   SELECT lpad(n::text, 43, '0'), lpad(n::text, 43, '0'), s.did, s.account_epoch, s.client_id, s.snapshot,
                          s.created_at, s.expires_at FROM oauth_sessions s CROSS JOIN generate_series(1, 60) n")
    (owner/mutate "INSERT INTO oauth_codes(code_hash, did, account_epoch, snapshot, created_at, expires_at)
                   SELECT lpad(n::text, 43, 'c'), c.did, c.account_epoch, c.snapshot, c.created_at, c.expires_at
                   FROM oauth_codes c CROSS JOIN generate_series(1, 1005) n")
    (expire!)
    (let [a (cleanup/collect! fixture/*ds*) b (cleanup/collect! fixture/*ds*)]
      (is (= 50 (:sessions a)))
      (is (= 11 (:sessions b)))
      (is (= 1000 (:codes a)))
      (is (= 6 (:codes b)))
      (is (= 2 (+ (:tokens a) (:tokens b)))))
    (is (= empty-sweep (cleanup/collect! fixture/*ds*)))))

(deftest cleanup-skips-in-flight-family-and-code-locks
  ;; Holds row locks across concurrent cleanup passes. SQLite serializes
  ;; writers for the whole transaction, so the overlap cannot be built.
  (when (fixture/postgres?)
  (let [env (token/env) _ (token/issue env (token/approved env))]
    (expire!)
    (with-open [conn (db/connection fixture/*ds*)]
      (.setAutoCommit conn false)
      (try
        (db/query conn "SELECT session_id FROM oauth_sessions FOR UPDATE")
        (let [sweep (future (cleanup/collect! fixture/*ds*))]
          (is (= empty-sweep (deref sweep 2000 :timeout))))
        (finally (.rollback conn))))
    (with-open [conn (db/connection fixture/*ds*)]
      (.setAutoCommit conn false)
      (try
        (db/query conn "SELECT token_hash FROM oauth_tokens LIMIT 1 FOR UPDATE")
        (let [sweep (future (cleanup/collect! fixture/*ds*))]
          (is (= {:tokens 1 :sessions 0 :codes 0} (deref sweep 2000 :timeout))))
        (finally (.rollback conn))))
    (with-open [conn (db/connection fixture/*ds*)]
      (.setAutoCommit conn false)
      (try
        (db/query conn "SELECT code_hash FROM oauth_codes FOR UPDATE")
        (let [sweep (future (cleanup/collect! fixture/*ds*))]
          (is (= {:tokens 1 :sessions 1 :codes 0} (deref sweep 2000 :timeout))))
        (finally (.rollback conn))))
    (is (= {:tokens 0 :sessions 0 :codes 1} (cleanup/collect! fixture/*ds*))))))

(deftest concurrent-sweeps-and-refresh-preserve-live-grants
  (let [env (token/env) issued (token/issue env (token/approved env))
        results (mapv deref [(future (cleanup/collect! fixture/*ds*))
                            (future (token/issue env (token/refresh-params issued)))
                            (future (cleanup/collect! fixture/*ds*))])]
    (is (= empty-sweep (first results) (last results)))
    (is (= owner/did (:did (token/access (second results)))))
    (expire!)
    (let [sweeps (mapv deref (mapv (fn [_] (future (cleanup/collect! fixture/*ds*))) (range 4)))]
      (is (= {:tokens 4 :sessions 1 :codes 1} (apply merge-with + sweeps))))
    (is (= empty-sweep (cleanup/collect! fixture/*ds*)))))

(deftest failed-batches-roll-back-and-orphan-cleanup-can-resume
  (let [env (token/env) _ (token/issue env (token/approved env)) execute db/execute!]
    (expire!)
    (with-redefs [db/execute! (fn [conn sql & args]
                              (if (.startsWith ^String sql "DELETE FROM oauth_sessions")
                                (throw (ex-info "Simulated failure" {}))
                                (apply execute conn sql args)))]
      (is (thrown? clojure.lang.ExceptionInfo (cleanup/collect! fixture/*ds*))))
    (is (= 2 (par/scalar "SELECT count(*) AS n FROM oauth_tokens")))
    (is (= 1 (par/scalar "SELECT count(*) AS n FROM oauth_sessions")))
    (with-redefs [db/execute! (fn [conn sql & args]
                              (if (.startsWith ^String sql "DELETE FROM oauth_codes")
                                (throw (ex-info "Simulated failure" {}))
                                (apply execute conn sql args)))]
      (is (thrown? clojure.lang.ExceptionInfo (cleanup/collect! fixture/*ds*))))
    (is (= 0 (par/scalar "SELECT count(*) AS n FROM oauth_tokens")))
    (is (= 0 (par/scalar "SELECT count(*) AS n FROM oauth_sessions")))
    (is (= 1 (par/scalar "SELECT count(*) AS n FROM oauth_codes")))
    (is (= {:tokens 0 :sessions 0 :codes 1} (cleanup/collect! fixture/*ds*)))))
