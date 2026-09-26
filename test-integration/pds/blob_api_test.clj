(ns pds.blob-api-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.server-api-test :as api])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))
(use-fixtures :each fixture/isolated-database)
(deftest blob-ownership-and-record-references
  (let [settings (api/settings)
        alice (accounts/create! fixture/*ds* settings {"handle" "alice.example.com" "email" "alice@example.com" "password" "test-password"})
        bob (accounts/create! fixture/*ds* settings {"handle" "bob.example.com" "email" "bob@example.com" "password" "test-password"})
        server (http/start! settings (app/handler settings fixture/*ds*)) port (:port server)
        bytes (byte-array [0 1 -1 -128 42])]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [request (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/xrpc/com.atproto.repo.uploadBlob")))
                          (.header "Authorization" (str "Bearer " (:accessJwt alice)))
                          (.header "Content-Type" "application/octet-stream")
                          (.POST (HttpRequest$BodyPublishers/ofByteArray bytes)) .build)
              response (.send client request (HttpResponse$BodyHandlers/ofString))
              blob (get (json/read-str (.body response)) "blob")
              cid (get-in blob ["ref" "$link"])
              download (api/xrpc client port "GET" (str "com.atproto.sync.getBlob?did=" (:did alice) "&cid=" cid) nil nil)
              body {"repo" (:did alice) "collection" "com.example.file" "rkey" "one"
                    "record" {"$type" "com.example.file" "file" blob}}]
          (is (= 200 (.statusCode response)))
          (is (= 400 (:status download)) "Temporary uploads are private")
          (is (= 400 (:status (api/xrpc client port "GET" (str "com.atproto.sync.getBlob?did=" (:did bob) "&cid=" cid) nil nil))))
          (is (= 200 (:status (api/xrpc client port "POST" "com.atproto.repo.createRecord" body (:accessJwt alice)))))
          (is (= (vec bytes) (vec (:raw (api/xrpc client port "GET" (str "com.atproto.sync.getBlob?did=" (:did alice) "&cid=" cid) nil nil)))))
          (let [read-request (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/xrpc/com.atproto.sync.getBlob?did=" (:did alice) "&cid=" cid))) .GET .build)
                read-response (.send client read-request (HttpResponse$BodyHandlers/ofByteArray))]
            (is (= "default-src 'none'; sandbox" (.orElse (.firstValue (.headers read-response) "Content-Security-Policy") "")))
            (is (= (str (alength bytes)) (.orElse (.firstValue (.headers read-response) "Content-Length") ""))))
          (is (= 400 (:status (api/xrpc client port "POST" "com.atproto.repo.createRecord" (assoc body "repo" (:did bob)) (:accessJwt bob)))))
          (is (= [cid] (get-in (api/xrpc client port "GET" (str "com.atproto.sync.listBlobs?did=" (:did alice)) nil nil) [:body "cids"])))))
      (finally ((:stop! server))))))
