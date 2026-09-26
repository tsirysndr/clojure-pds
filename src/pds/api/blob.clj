(ns pds.api.blob
  (:require [clojure.string :as str]
            [pds.accounts :as accounts]
            [pds.api.repo :as repo-api]
            [pds.api.server :as server]
            [pds.blobs :as blobs]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.request :as request]))

(def max-size blobs/max-size)
(defn routes [ds settings]
  {"/xrpc/com.atproto.repo.uploadBlob"
   (server/json-route
    :post
    (server/authenticated
     ds settings
     (fn [conn account r]
       (let [type (some-> (get-in r [:headers "content-type"]) (str/split #";") first str/lower-case)
             _ (when-not (and type (re-matches #"[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+" type))
                 (errors/invalid! "A valid Content-Type is required"))
             bytes (request/body-bytes r max-size)]
         (when (zero? (alength bytes)) (errors/invalid! "Blob must not be empty"))
         (let [stored (blobs/store! conn settings (:did account) bytes type)]
           {:blob {:$type "blob" :ref {:$link (:cid stored)} :mimeType (:mime_type stored) :size (:size stored)}})))))
   "/xrpc/com.atproto.sync.getBlob"
   {:method :get
    :handler
    (fn [r]
      (let [params (request/query-params r)]
        (with-open [conn (db/connection ds)]
          (let [account (accounts/resolve-account conn (get params "did"))
                id (repo-api/cid! (get params "cid"))
                blob (blobs/read! conn settings (:did account) id)]
            {:status 200
             :headers {"Content-Type" (:mime_type blob) "X-Content-Type-Options" "nosniff"
                       "Content-Disposition" "attachment"}
             :body (:content blob)}))))}
   "/xrpc/com.atproto.sync.listBlobs"
   (repo-api/query-route
    ds
    (fn [conn params]
      (when (get params "since")
        (errors/raise! 400 "InvalidRequest" "Incremental blob listing is not implemented"))
      (let [account (accounts/resolve-account conn (get params "did"))
            limit (request/limit! params 500 1000) cursor (get params "cursor")
            _ (when cursor (repo-api/cid! cursor))
            rows (db/query conn "SELECT cid FROM blobs WHERE did = ? AND (?::text IS NULL OR cid COLLATE \"C\" > ? COLLATE \"C\") ORDER BY cid COLLATE \"C\" LIMIT ?"
                           (:did account) cursor cursor (inc limit))
            page (repo-api/paginated rows limit :cid :cid)]
        (-> page (assoc :cids (:items page)) (dissoc :items)))))})
