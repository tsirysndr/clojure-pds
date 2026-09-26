(ns pds.api.repo
  (:require [clojure.string :as str]
            [pds.accounts :as accounts]
            [pds.api.server :as server]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.protocol.car :as car]
            [pds.protocol.codec :as codec]
            [pds.protocol.syntax :as syntax]
            [pds.repo :as repo]
            [pds.request :as request]))

(defn own-repo! [account body]
  (when-not (#{(:did account) (:handle account)} (get body "repo"))
    (errors/raise! 403 "AuthRequired" "Cannot write to another account's repository")))
(defn validation! [body]
  (when (and (contains? body "validate") (not (boolean? (get body "validate"))))
    (errors/invalid! "validate must be a boolean")))
(defn cid! [value]
  (try (codec/cid-bytes (request/string! value "cid")) value
       (catch Exception _ (errors/invalid! "Invalid CID"))))
(defn write [action body]
  {:action action :collection (get body "collection") :rkey (get body "rkey") :value (get body "record" (get body "value"))
   :validate (get body "validate")
   :swap-record? (contains? body "swapRecord") :swap-record (get body "swapRecord")})
(defn write-route [ds settings action]
  (server/json-route
   :post
   (server/authenticated
    ds settings
    (fn [conn account r]
      (let [body (request/json-body r)]
        (own-repo! account body) (validation! body)
        (let [result (repo/apply-writes! conn settings (:did account) [(write action body)] (get body "swapCommit"))]
          (if (= action :delete) {:commit (:commit result)}
              (assoc (dissoc (first (:results result)) :$type) :commit (:commit result)))))))))
(defn query-route [ds f]
  (server/json-route :get
    (fn [r] (with-open [conn (db/connection ds)] (f conn (request/query-params r))))))
(defn paginated [rows limit key-fn item-fn]
  (let [page (vec (take limit rows))]
    (cond-> {:items (mapv item-fn page)}
      (> (count rows) limit) (assoc :cursor (key-fn (last page))))))
(defn routes [ds settings]
  {"/xrpc/com.atproto.repo.createRecord" (write-route ds settings :create)
   "/xrpc/com.atproto.repo.putRecord" (write-route ds settings :put)
   "/xrpc/com.atproto.repo.deleteRecord" (write-route ds settings :delete)
   "/xrpc/com.atproto.repo.applyWrites"
   (server/json-route :post
     (server/authenticated ds settings
       (fn [conn account r]
         (let [body (request/json-body r) writes (get body "writes")]
           (own-repo! account body) (validation! body)
           (when-not (and (vector? writes) (<= 1 (count writes) 200)) (errors/invalid! "Expected 1 to 200 writes"))
           (repo/apply-writes!
            conn settings (:did account)
            (mapv (fn [entry]
                    (when-not (map? entry) (errors/invalid! "Invalid write"))
                    (write (case (get entry "$type")
                             "com.atproto.repo.applyWrites#create" :create
                             "com.atproto.repo.applyWrites#update" :update
                             "com.atproto.repo.applyWrites#delete" :delete
                             (errors/invalid! "Unknown write type")) (assoc entry "validate" (get body "validate")))) writes)
            (get body "swapCommit"))))))
   "/xrpc/com.atproto.repo.getRecord"
   (query-route ds (fn [conn params]
                    (let [account (accounts/resolve-account conn (get params "repo"))
                          record (repo/record conn (:did account) (get params "collection") (get params "rkey"))]
                      (when-not (and record (or (nil? (get params "cid")) (= (:cid record) (get params "cid"))))
                        (errors/raise! 400 "RecordNotFound" "Record was not found")) record)))
   "/xrpc/com.atproto.repo.listRecords"
   (query-route
    ds
    (fn [conn params]
      (let [account (accounts/resolve-account conn (get params "repo"))
            collection (get params "collection") limit (request/limit! params 50 100)
            cursor (get params "cursor") reverse (get params "reverse" "false")]
        (when-not (syntax/nsid? collection) (errors/invalid! "Invalid collection"))
        (when-not (#{"true" "false"} reverse) (errors/invalid! "reverse must be true or false"))
        (when (and cursor (not (syntax/record-key? cursor))) (errors/invalid! "Invalid cursor"))
        (let [rows (db/query conn (str "SELECT r.rkey, r.cid, b.content FROM records r JOIN repo_blocks b ON b.cid = r.cid
                                        WHERE r.did = ? AND r.collection = ? AND (?::text IS NULL OR r.rkey COLLATE \"C\" "
                                       (if (= "true" reverse) "<" ">") " ? COLLATE \"C\") ORDER BY r.rkey COLLATE \"C\" "
                                       (if (= "true" reverse) "DESC" "ASC") " LIMIT ?")
                             (:did account) collection cursor cursor (inc limit))
              page (paginated rows limit :rkey
                              (fn [row] {:uri (str "at://" (:did account) "/" collection "/" (:rkey row)) :cid (:cid row)
                                         :value (codec/to-json (codec/decode (:content row)))}))]
          (-> page (assoc :records (:items page)) (dissoc :items))))))
   "/xrpc/com.atproto.repo.describeRepo"
   (query-route ds (fn [conn params]
                    (let [account (accounts/resolve-account conn (get params "repo"))
                          key (:public_key (first (db/query conn "SELECT public_key FROM repositories WHERE did = ?" (:did account))))]
                      {:did (:did account) :handle (:handle account) :didDoc (accounts/did-document settings account key)
                       :collections (mapv :collection (db/query conn "SELECT DISTINCT collection FROM records WHERE did = ? ORDER BY collection" (:did account)))
                       :handleIsCorrect true})))
   "/xrpc/com.atproto.sync.getLatestCommit"
   (query-route ds (fn [conn params]
                    (let [account (accounts/resolve-account conn (get params "did"))
                          state (first (db/query conn "SELECT head, rev FROM repositories WHERE did = ?" (:did account)))]
                      {:cid (:head state) :rev (:rev state)})))
   "/xrpc/com.atproto.sync.getRepoStatus"
   (query-route ds (fn [conn params]
                    (let [did (get params "did")
                          account (first (db/query conn "SELECT a.status, r.rev FROM accounts a LEFT JOIN repositories r ON a.did = r.did WHERE a.did = ?"
                                                   (request/string! did "did")))]
                      (when-not account (errors/raise! 400 "RepoNotFound" "Repository was not found"))
                      (cond-> {:did did :active (= "active" (:status account))}
                        (:rev account) (assoc :rev (:rev account))
                        (not= "active" (:status account)) (assoc :status (if (= "taken_down" (:status account)) "takendown" (:status account)))))))
   "/xrpc/com.atproto.sync.listRepos"
   (query-route ds (fn [conn params]
                    (let [limit (request/limit! params 500 1000) cursor (get params "cursor")
                          rows (db/query conn "SELECT r.did, r.head, r.rev FROM repositories r JOIN accounts a ON a.did = r.did
                                              WHERE a.status = 'active' AND (?::text IS NULL OR r.did COLLATE \"C\" > ? COLLATE \"C\")
                                              ORDER BY r.did COLLATE \"C\" LIMIT ?" cursor cursor (inc limit))
                          page (paginated rows limit :did #(assoc % :active true))]
                      (-> page (assoc :repos (:items page)) (dissoc :items)))))
   "/xrpc/com.atproto.sync.getRepo"
   {:method :get :handler (fn [r]
                           (let [params (request/query-params r)]
                             (db/transact! ds
                               (fn [conn]
                                 (let [account (accounts/resolve-account conn (get params "did"))]
                                   {:status 200 :headers {"Content-Type" "application/vnd.ipld.car"}
                                    ;; A full export is also a valid response to an incremental request.
                                    :body (repo/export-car conn (:did account))})))))}})
