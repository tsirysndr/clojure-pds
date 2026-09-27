(ns pds.api.blob
  (:require [clojure.string :as str]
            [pds.accounts :as accounts]
            [pds.api.repo :as repo-api]
            [pds.api.server :as server]
            [pds.blobs :as blobs]
            [pds.blob-refs :as blob-refs]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.oauth.permissions :as permissions]
            [pds.protocol.syntax :as syntax]
            [pds.request :as request]))

(def max-size blobs/max-size)
(defn routes [ds settings]
  {"/xrpc/com.atproto.repo.uploadBlob"
   (server/json-route
    :post
    (server/authenticated
     ds settings {:allow-deactivated? true}
     (fn [conn account r]
       (let [type (some-> (get-in r [:headers "content-type"]) (str/split #";") first str/lower-case)
             _ (when-not (and type (re-matches #"[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+" type))
                 (errors/invalid! "A valid Content-Type is required"))
             _ (permissions/blob! account type)
             bytes (request/body-bytes r max-size)]
         (let [stored (blobs/store! conn settings (:did account) bytes type)]
           {:blob {:$type "blob" :ref {:$link (:cid stored)} :mimeType (:mime_type stored) :size (:size stored)}})))))
   "/xrpc/com.atproto.repo.listMissingBlobs"
   (server/json-route :get
     (server/authenticated ds settings {:allow-deactivated? true}
       (fn [conn account r]
         (let [params (request/query-params r) limit (request/limit! params 500 1000)
               cursor (get params "cursor") _ (when cursor (repo-api/cid! cursor))
               rows (blob-refs/missing conn (:did account) cursor (inc limit))
               page (repo-api/paginated rows limit :cid
                       (fn [row] {:cid (:cid row) :recordUri (str "at://" (:did account) "/" (:path row))}))]
           (-> page (assoc :blobs (:items page)) (dissoc :items))))))
   "/xrpc/com.atproto.sync.getBlob"
   {:method :get
    :handler
    (fn [r]
      (let [params (request/query-params r)]
        (with-open [conn (db/connection ds)]
          (let [account (accounts/resolve-account conn (get params "did"))
                id (repo-api/cid! (get params "cid"))
                _ (when-not (blobs/referenced? conn (:did account) id)
                    (errors/raise! 400 "BlobNotFound" "Blob was not found"))
                blob (blobs/read! conn settings (:did account) id)]
            {:status 200
             :headers {"Content-Type" (:mime_type blob) "X-Content-Type-Options" "nosniff"
                       "Content-Length" (str (:size blob)) "Content-Security-Policy" "default-src 'none'; sandbox"
                       "Content-Disposition" "attachment"}
             :body (:content blob)}))))}
   "/xrpc/com.atproto.sync.listBlobs"
   (repo-api/query-route
    ds
    (fn [conn params]
      (let [did (get params "did") since (get params "since")
            _ (when-not (syntax/did? did) (errors/invalid! "Invalid DID"))
            _ (when (and since (not (syntax/tid? since))) (errors/invalid! "since must be a repository revision TID"))
            account (accounts/resolve-account conn did)
            limit (request/limit! params 500 1000) cursor (get params "cursor")
            _ (when cursor (repo-api/cid! cursor))
            rows (db/query conn "SELECT b.cid FROM record_blob_refs b JOIN records r
                                 ON r.did = b.did AND r.collection = b.collection AND r.rkey = b.rkey
                                 WHERE b.did = ? AND (?::text IS NULL OR b.cid COLLATE \"C\" > ? COLLATE \"C\")
                                   AND (?::text IS NULL OR r.repo_rev IS NULL OR r.repo_rev COLLATE \"C\" > ? COLLATE \"C\")
                                 GROUP BY b.cid ORDER BY b.cid COLLATE \"C\" LIMIT ?"
                           (:did account) cursor cursor since since (inc limit))
            page (repo-api/paginated rows limit :cid :cid)]
        (-> page (assoc :cids (:items page)) (dissoc :items)))))})
