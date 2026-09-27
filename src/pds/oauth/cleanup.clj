(ns pds.oauth.cleanup
  "Bounded grant retention. Keep every token and its used authorization code until
  the family's absolute expiry, including revoked families and rotated tokens."
  (:require [pds.db :as db]
            [pds.oauth.dpop :as dpop])
  (:import [java.time Instant]
           [java.util.concurrent Executors TimeUnit]))

(defn- check-interrupted! []
  (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException.))))

(defn collect! [ds]
  (let [now (Instant/ofEpochSecond (dpop/now))
        families
        (db/transact! ds
          (fn [conn]
            (db/execute! conn "SET LOCAL statement_timeout = '5s'")
            ;; Session -> token matches refresh/resource locking. No account or
            ;; code lock is acquired while these locks are held.
            (let [rows (db/query conn "SELECT session_id FROM oauth_sessions WHERE expires_at <= ?
                                       ORDER BY expires_at, session_id LIMIT 50 FOR UPDATE SKIP LOCKED" now)]
              (reduce
                (fn [{:keys [tokens] :as counts} {:keys [session_id]}]
                  (check-interrupted!)
                  (if (>= tokens 1000) (reduced counts)
                    (let [deleted (db/execute! conn
                                    "DELETE FROM oauth_tokens WHERE token_hash IN
                                     (SELECT token_hash FROM oauth_tokens WHERE session_id = ?
                                      LIMIT ? FOR UPDATE SKIP LOCKED)" session_id (- 1000 tokens))
                          ;; Avoid an unbounded ON DELETE CASCADE, even if a
                          ;; family's refresh history spans many sweeps.
                          removed (db/execute! conn
                                    "DELETE FROM oauth_sessions WHERE session_id = ?
                                     AND NOT EXISTS (SELECT 1 FROM oauth_tokens WHERE session_id = ?)"
                                    session_id session_id)]
                      (-> counts (update :tokens + deleted) (update :sessions + removed)))))
                {:tokens 0 :sessions 0} rows))))
        ;; Separate transaction: code exchange/replay locks code -> session.
        ;; Taking code locks under the family locks would reverse that order.
        codes (db/transact! ds
                (fn [conn]
                  (check-interrupted!)
                  (db/execute! conn "SET LOCAL statement_timeout = '5s'")
                  (db/execute! conn
                    "DELETE FROM oauth_codes WHERE code_hash IN
                     (SELECT c.code_hash FROM oauth_codes c WHERE c.expires_at <= ?
                      AND NOT EXISTS (SELECT 1 FROM oauth_sessions s WHERE s.code_hash = c.code_hash)
                      ORDER BY c.expires_at, c.code_hash LIMIT 1000 FOR UPDATE OF c SKIP LOCKED)" now)))]
    (assoc families :codes codes)))

(defn start! [ds]
  (let [executor (Executors/newSingleThreadScheduledExecutor)]
    (.scheduleWithFixedDelay executor
      ^Runnable (fn [] (try (collect! ds)
                           (catch InterruptedException _ (.interrupt (Thread/currentThread)))
                           (catch Exception _
                             (binding [*out* *err*] (println "OAuth grant cleanup failed; retrying")))))
      60 60 TimeUnit/SECONDS)
    (fn [] (.shutdownNow executor) (.awaitTermination executor 15 TimeUnit/SECONDS))))
