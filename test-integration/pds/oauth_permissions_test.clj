(ns pds.oauth-permissions-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.app :as app]
            [pds.db-test :as fixture]
            [pds.oauth-interaction-test :as owner]
            [pds.oauth-par-test :as par]
            [pds.oauth-resource-test :as resource]
            [pds.oauth-tokens-test :as token]
            [pds.proxy-test :as upstream]
            [pds.service-auth-test :as jwt])
  (:import [java.io ByteArrayInputStream InputStream]))

(use-fixtures :each fixture/isolated-database)
(defn mint! [env scope]
  (swap! (:document env) update "scope" #(str/join " " (sort (set (concat (str/split % #" ") (str/split scope #" "))))))
  (token/issue env (token/approved env {"scope" scope})))
(defn call! [handler env issued method body]
  (resource/call handler (resource/request env issued :post (str "/xrpc/com.atproto.repo." method)) body))
(defn note [key] (assoc resource/record-body "rkey" key))

(deftest repository-actions-are-checked-under-the-write-lock
  (let [env (token/env) create (mint! env "atproto repo:com.example.note?action=create")
        update (mint! env "atproto repo:com.example.note?action=update")
        delete (mint! env "atproto repo:com.example.note?action=delete")
        handler (app/handler (resource/settings env) fixture/*ds*)]
    (is (= 200 (:status (call! handler env create "createRecord" (note "one")))))
    (is (= 403 (:status (call! handler env create "putRecord" (note "one")))))
    (is (= 200 (:status (call! handler env create "putRecord" (note "two")))))
    (is (= 403 (:status (call! handler env update "putRecord" (note "new")))))
    (is (= 403 (:status (call! handler env update "createRecord" (note "new")))))
    (is (= 200 (:status (call! handler env update "putRecord" (assoc-in (note "one") ["record" "text"] "Updated")))))
    (is (= 403 (:status (call! handler env create "deleteRecord" (note "one")))))
    (is (= 200 (:status (call! handler env delete "deleteRecord" (note "one")))))
    (is (= 403 (:status (call! handler env create "createRecord"
                               (-> (note "other") (assoc "collection" "com.example.other") (assoc-in ["record" "$type"] "com.example.other"))))))
    (is (= [{:rkey "two"}] (token/query "SELECT rkey FROM records ORDER BY rkey")))
    (let [wildcard (mint! env "atproto repo:*?action=delete")]
      (is (= 200 (:status (call! handler env wildcard "deleteRecord" (note "two")))))
      (is (= 403 (:status (call! handler env wildcard "createRecord" (note "denied"))))))))

(deftest mixed-batch-denial-rolls-back-records-blocks-commit-and-events
  (let [env (token/env) issued (mint! env "atproto repo:com.example.note?action=create")
        handler (app/handler (resource/settings env) fixture/*ds*)
        before (token/query "SELECT head, rev FROM repositories") blocks (par/scalar "SELECT count(*) AS n FROM repo_blocks")
        events (par/scalar "SELECT count(*) AS n FROM repo_events")
        create {"$type" "com.atproto.repo.applyWrites#create" "collection" "com.example.note" "rkey" "allowed"
                "value" {"$type" "com.example.note" "text" "Must roll back"}}
        delete {"$type" "com.atproto.repo.applyWrites#delete" "collection" "com.example.note" "rkey" "missing"}
        response (call! handler env issued "applyWrites" {"repo" owner/did "validate" false "writes" [create delete]})]
    (is (= 403 (:status response)))
    (is (= "insufficient_scope" (get-in response [:json "error"])))
    (is (empty? (token/query "SELECT * FROM records")))
    (is (= before (token/query "SELECT head, rev FROM repositories")))
    (is (= blocks (par/scalar "SELECT count(*) AS n FROM repo_blocks")))
    (is (= events (par/scalar "SELECT count(*) AS n FROM repo_events")))
    (is (= 200 (:status (call! handler env issued "applyWrites" {"repo" owner/did "validate" false "writes" [create]}))))))

(deftest blob-mime-permission-is-checked-before-reading-or-storing
  (let [env (token/env) images (mint! env "atproto blob:image/*")
        json (mint! env "atproto blob:application/ld+json")
        handler (app/handler (resource/settings env) fixture/*ds*) path "/xrpc/com.atproto.repo.uploadBlob"
        request (fn [issued type body] (-> (resource/request env issued :post path)
                                           (assoc-in [:headers "content-type"] type) (assoc :body body)))
        unread (proxy [InputStream] [] (read [] (throw (AssertionError. "Denied upload must not be read"))))]
    (is (= 403 (:status (handler (request images "text/html" unread)))))
    (is (empty? (token/query "SELECT * FROM blobs")))
    (is (= 200 (:status (handler (request images "image/png" (ByteArrayInputStream. (byte-array [1 2 3])))))))
    (is (= 200 (:status (handler (request json "application/ld+json" (ByteArrayInputStream. (.getBytes "{}" "UTF-8")))))))
    (is (= 403 (:status (handler (request json "image/png" unread)))))
    (is (= #{"image/png" "application/ld+json"} (set (map :mime_type (token/query "SELECT mime_type FROM blobs")))))))

(deftest granular-email-and-refresh-narrowing
  (let [env (token/env) scope "atproto repo:com.example.note?action=create account:email"
        issued (mint! env scope) handler (app/handler (resource/settings env) fixture/*ds*)
        refreshed (token/issue env (assoc (token/refresh-params issued) "scope" "atproto account:email"))]
    (is (= "alice@example.com" (get-in (resource/call handler (resource/request env refreshed :get resource/session-path)) [:json "email"])))
    (is (= 403 (:status (call! handler env refreshed "createRecord" (note "denied")))))
    (is (= 200 (:status (call! handler env issued "createRecord" (note "allowed")))) "Existing access token retains its original permission")
    (doseq [[method path] [[:post "/xrpc/com.atproto.server.updateEmail"] [:post "/xrpc/com.atproto.identity.updateHandle"]
                           [:post "/xrpc/com.atproto.repo.importRepo"]]]
      (is (= 403 (:status (resource/call handler (resource/request env refreshed method path) {"handle" "other.example.com"})))))))

(deftest rpc-binds-method-and-audience-before-remote-lookup
  (upstream/with-service
    (fn [{:keys [client fetch calls]}]
      (let [env (token/env) scope (str "atproto rpc:com.example.read?aud=" (str/replace upstream/audience "#" "%23"))
            issued (mint! env scope) lookups (atom 0)
            handler (app/handler (assoc (resource/settings env) :http-client client :proxy-appview-service upstream/audience
                                       :fetch (fn [& args] (swap! lookups inc) (apply fetch args))) fixture/*ds*)
            invoke (fn [path] (resource/call handler (resource/request env issued :get path)))
            service-path (str "/xrpc/com.atproto.server.getServiceAuth?aud=" (str/replace upstream/audience "#" "%23"))]
        (is (= 403 (:status (invoke "/xrpc/com.example.other"))))
        (is (= 403 (:status (resource/call handler (assoc-in (resource/request env issued :get "/xrpc/com.example.read")
                                                          [:headers "atproto-proxy"] "did:web:other.example.com#appview")))))
        (is (= 403 (:status (invoke (str service-path "&lxm=com.example.other")))))
        (is (= 403 (:status (invoke (str "/xrpc/com.atproto.server.getServiceAuth?aud=" upstream/service-did "&lxm=com.example.read")))))
        (is (zero? @lookups))
        (is (empty? @calls))
        (let [result (invoke (str service-path "&lxm=com.example.read"))
              claims (:claims (jwt/decode (get-in result [:json "token"])))]
          (is (= 200 (:status result)))
          ;; A delegated token keeps the requested service reference verbatim;
          ;; only the proxied request below carries the bare DID.
          (is (= upstream/audience (get claims "aud")))
          (is (= "com.example.read" (get claims "lxm"))))
        (is (= 200 (:status (invoke "/xrpc/com.example.read"))))
        (is (= 1 (count @calls)))
        (is (= (first (clojure.string/split upstream/audience #"#" 2))
               (get-in (jwt/decode (subs (get-in (first @calls) [:headers "authorization"]) 7)) [:claims "aud"])))))))
