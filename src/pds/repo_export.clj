(ns pds.repo-export
  (:require [pds.accounts :as accounts]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.protocol.car :as car]
            [pds.protocol.codec :as codec]
            [pds.protocol.syntax :as syntax]
            [pds.repo :as repo]
            [pds.response-body :as body]
            [pds.tempfile :as tempfile])
  (:import [java.io FilterInputStream IOException OutputStream]
           [java.nio.channels Channels FileChannel]
           [java.util.concurrent Semaphore]))

(def default-max-bytes (* 256 1024 1024))
(defonce ^:private permits (Semaphore. 2))

(defn settings [env]
  (let [value (get env "PDS_REPO_EXPORT_MAX_BYTES" (str default-max-bytes))
        n (try (Long/parseLong value) (catch Exception _ 0))]
    (when-not (<= (* 1024 1024) n (* 16 1024 1024 1024))
      (throw (ex-info "PDS_REPO_EXPORT_MAX_BYTES must be between 1048576 and 17179869184" {})))
    {:repo-export-max-bytes n}))

(defn- bounded-output [^FileChannel channel maximum]
  (let [out (Channels/newOutputStream channel) count (atom 0)
        write! (fn [^bytes bytes offset length]
                 (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException.)))
                 (when (> (+ @count length) maximum)
                   (errors/raise! 413 "PayloadTooLarge" "Repository export exceeds the configured limit"))
                 (.write out bytes (int offset) (int length))
                 (swap! count + length))]
    (proxy [OutputStream] []
      (write
        ([value] (if (bytes? value) (write! value 0 (alength ^bytes value))
                     (write! (byte-array [(unchecked-byte value)]) 0 1)))
        ([bytes offset length] (write! bytes offset length))))))

(defn- read-block! [conn did cid]
  (or (:content (first (db/query conn
          "SELECT b.content FROM repo_blocks b JOIN repo_block_owners o ON o.cid = b.cid
           WHERE o.did = ? AND b.cid = ? AND octet_length(b.content) <= 1048576" did cid)))
      (codec/fail! "Missing, oversized or unowned repository block")))

(defn- link! [value nullable?]
  (when-not (or (and nullable? (nil? value))
                (and (instance? pds.protocol.codec.Link value)
                     (= 113 (aget (codec/cid-bytes (:cid value)) 1))))
    (codec/fail! "Invalid repository graph link"))
  (:cid value))

(defn write-repo!
  "Caller owns an account/repository-locked transaction. Traverse only committed
  MST edges and record leaves; arbitrary record links never expand ownership.
  A transaction-local PostgreSQL set deduplicates CIDs without a repository-sized
  JVM map. Temporary table and locks disappear before HTTP delivery."
  [conn did out]
  (let [state (repo/state conn did)
        _ (accounts/resolve-account conn did)
        head (:head state) data (read-block! conn did head) commit (codec/decode data)]
    (when-not (and (= did (get commit "did")) (= 3 (get commit "version")) (= (:rev state) (get commit "rev")))
      (codec/fail! "Repository commit does not match its state"))
    (db/execute! conn "CREATE TEMPORARY TABLE pds_car_export_seen (cid text PRIMARY KEY) ON COMMIT DROP")
    (car/write-header! out head)
    (letfn [(emit! [cid]
              (when (pos? (db/execute! conn "INSERT INTO pds_car_export_seen VALUES (?) ON CONFLICT DO NOTHING" cid))
                (let [data (read-block! conn did cid)]
                  (car/write-block! out cid data)
                  data)))
            (walk! [cid depth]
              (when (> depth 128) (codec/fail! "Repository tree is too deep"))
              (when-let [data (emit! cid)]
                (let [node (codec/decode data) entries (get node "e")]
                  (when-not (and (map? node) (= #{"l" "e"} (set (keys node))) (vector? entries))
                    (codec/fail! "Invalid repository tree node"))
                  (when-let [left (link! (get node "l") true)] (walk! left (inc depth)))
                  (doseq [entry entries]
                    (when-not (and (map? entry) (= #{"p" "k" "v" "t"} (set (keys entry))))
                      (codec/fail! "Invalid repository tree entry"))
                    (emit! (link! (get entry "v") false))
                    (when-let [child (link! (get entry "t") true)] (walk! child (inc depth)))))))]
      (emit! head)
      (walk! (link! (get commit "data") false) 0))))

(defn staged-response!
  "Build a verified CAR with a caller-supplied OutputStream writer. The writer
  must finish its transaction before returning. Full and partial CAR responses
  share the process-wide allowance until their owned HTTP bodies are closed."
  [maximum write!]
  (when-not (.tryAcquire permits)
    (errors/raise! 503 "RepoExportBusy" "Repository export capacity is busy; retry later"))
  (let [channel (atom nil) released? (atom false)
        release! #(when (compare-and-set! released? false true)
                    (try (when-let [^FileChannel c @channel] (.close c))
                         (finally (.release permits))))]
    (try
      (reset! channel (tempfile/open-channel!))
      (write! (bounded-output @channel maximum))
      (let [length (.size ^FileChannel @channel)
            input (proxy [FilterInputStream] [(tempfile/input @channel)] (close [] (release!)))]
        {:status 200 :headers {"Content-Type" "application/vnd.ipld.car" "Content-Length" (str length)}
         :body (body/stream input length)})
      (catch Throwable error
        (try (release!) (catch Throwable _))
        (if (instance? IOException error)
          (errors/raise! 503 "RepoExportUnavailable" "Repository export storage is unavailable")
          (throw error))))))

(defn response! [ds config did]
  (when-not (syntax/did? did) (errors/invalid! "Invalid DID"))
  (staged-response! (get config :repo-export-max-bytes default-max-bytes)
    (fn [out] (db/transact! ds #(write-repo! % did out)))))
