(ns pds.oauth-management-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.crypto :as crypto]
            [pds.db-test :as fixture]
            [pds.oauth-interaction-test :as owner]
            [pds.oauth-permissions-test :as grants]
            [pds.oauth-resource-test :as resource]
            [pds.oauth-tokens-test :as token]
            [pds.oauth.client :as client]
            [pds.oauth.client-test :as metadata]
            [pds.oauth.dpop-test :as proof]
            [pds.plc :as plc]
            [pds.plc-directory-test :as directory]
            [pds.plc-provision-test :as provision]
            [pds.plc-signing-test :as signing]
            [pds.protocol.repository :as repository]
            [pds.repo-import-test :as imports]
            [pds.server-api-test :as api])
  (:import [java.io ByteArrayInputStream InputStream]))

(use-fixtures :each fixture/isolated-database)
(defn invoke [handler env issued method nsid & [body]]
  (resource/call handler (resource/request env issued method (str "/xrpc/" nsid)) body))

(deftest email-management-requires-the-grant-and-existing-email-proof
  (let [env (token/env) read (grants/mint! env "atproto account:email")
        manage (grants/mint! env "atproto account:email?action=manage")
        handler (app/handler (resource/settings env) fixture/*ds*)
        post #(invoke handler env %1 :post (str "com.atproto.server." %2) %3)]
    (doseq [method ["requestEmailConfirmation" "confirmEmail" "requestEmailUpdate" "updateEmail"]]
      (is (= 403 (:status (post read method {"email" "alice@example.com" "token" "wrong"})))))
    (is (= 403 (:status (post manage "createAppPassword" {"name" "escape"}))))
    (is (= 403 (:status (post manage "deactivateAccount" {}))))
    (is (= 200 (:status (post manage "requestEmailConfirmation" {}))))
    (is (= 400 (:status (post manage "confirmEmail" {"email" "alice@example.com" "token" "wrong"}))))
    (is (= 200 (:status (post manage "confirmEmail" {"email" "alice@example.com" "token" (api/email-token "Confirm your PDS email")}))))
    (is (= 401 (:status (invoke handler env manage :get "com.atproto.server.getSession"))) "Confirmation advances the account epoch")
    (let [manage (grants/mint! env "atproto account:email?action=manage")
          requested (post manage "requestEmailUpdate" {}) email-token (api/email-token "Update your PDS email")]
      (is (= true (get-in requested [:json "tokenRequired"])))
      (is (= 400 (:status (post manage "updateEmail" {"email" "new@example.com"}))))
      (is (= 403 (:status (post manage "updateEmail" {"email" "alice@example.com" "token" email-token "emailAuthFactor" true}))))
      (is (= 400 (:status (post manage "updateEmail" {"email" "new@example.com" "token" "wrong"}))))
      (is (= 200 (:status (post manage "updateEmail" {"email" "new@example.com" "token" email-token}))))
      (is (= [{:email "new@example.com" :email_confirmed false :email_auth_factor false}]
             (token/query "SELECT email, email_confirmed, email_auth_factor FROM accounts")))
      (is (every? :revoked (token/query "SELECT revoked FROM sessions")) "OAuth session ID must never be compared to legacy UUIDs")
      (is (= 401 (:status (invoke handler env manage :get "com.atproto.server.getSession"))))
      (is (string? (api/email-token "Confirm your PDS email"))))))

(deftest import-authority-is-separate-from-record-writes-and-rechecked-after-verification
  (let [env (token/env) writes (grants/mint! env "atproto repo:*")
        read (grants/mint! env "atproto account:repo") manage (grants/mint! env "atproto account:repo?action=manage")
        handler (app/handler (resource/settings env) fixture/*ds*)
        key (imports/local-key (:settings env) owner/did)
        car (:car (imports/repo-car owner/did key {"com.example.note/imported" {"$type" "com.example.note" "text" "Imported"}}))
        request (fn [issued body] (-> (resource/request env issued :post "/xrpc/com.atproto.repo.importRepo")
                                     (assoc-in [:headers "content-type"] "application/vnd.ipld.car") (assoc :body body)))
        unread (proxy [InputStream] [] (read [] (throw (AssertionError. "Denied import must not read its body"))))]
    (doseq [issued [read writes]] (is (= 403 (:status (handler (request issued unread))))))
    (is (= 200 (:status (handler (request manage (ByteArrayInputStream. car))))))
    (is (= [{:rkey "imported"}] (token/query "SELECT rkey FROM records")))
    (is (= 403 (:status (grants/call! handler env manage "createRecord" (grants/note "denied")))))
    (let [before (imports/state) verify repository/verify-blocks]
      (with-redefs [repository/verify-blocks (fn [& args]
                                           (owner/mutate "UPDATE oauth_sessions SET revoked_at = now()")
                                           (apply verify args))]
        (is (= 401 (:status (handler (request manage (ByteArrayInputStream. car)))))))
      (is (= before (imports/state))))))

(deftest handle-permission-does-not-grant-full-identity-or-account-control
  (let [env (token/env) basic (grants/mint! env "atproto") handle (grants/mint! env "atproto identity:handle")
        handler (app/handler (resource/settings env) fixture/*ds*)]
    (is (= 200 (:status (invoke handler env basic :get "com.atproto.identity.getRecommendedDidCredentials"))))
    (is (= 200 (:status (invoke handler env basic :get "com.atproto.repo.listMissingBlobs"))))
    (is (= 403 (:status (invoke handler env basic :post "com.atproto.identity.updateHandle" {"handle" "alicia.example.com"}))))
    (is (= 200 (:status (invoke handler env handle :post "com.atproto.identity.updateHandle" {"handle" "alicia.example.com"}))))
    (is (= [{:handle "alicia.example.com"}] (token/query "SELECT handle FROM accounts")))
    (doseq [[nsid body] [["com.atproto.identity.requestPlcOperationSignature" {}]
                         ["com.atproto.identity.signPlcOperation" {"token" "wrong"}]
                         ["com.atproto.server.updateEmail" {"email" "new@example.com"}]
                         ["com.atproto.server.createAppPassword" {"name" "escape"}]
                         ["com.atproto.server.deactivateAccount" {}]]]
      (is (= 403 (:status (invoke handler env handle :post nsid body))) nsid))))

(deftest full-identity-grant-signs-and-submits-with-email-and-directory-validation
  (directory/with-directory
    (fn [{:keys [client origin calls]}]
      (let [settings (merge (provision/settings client origin) proof/settings)
            account (accounts/create! fixture/*ds* settings (provision/signup)) did (:did account)
            doc (atom (metadata/metadata)) env {:settings settings :key (crypto/keypair) :document doc
                                              :resolver (client/resolver {:oauth-client-fetch (fn [_ _] (metadata/response @doc))})}]
        (with-redefs [owner/credentials {"identifier" did "password" "signup-password"}]
          (let [handle (grants/mint! env "atproto identity:handle") full (grants/mint! env "atproto identity:*")
                handler (app/handler (resource/settings env) fixture/*ds*)
                post #(invoke handler env %1 :post (str "com.atproto.identity." %2) %3)]
            (is (= 200 (:status (post handle "updateHandle" {"handle" "alicia.example.com"}))))
            (is (= 403 (:status (post handle "requestPlcOperationSignature" {}))))
            (is (= 200 (:status (post full "requestPlcOperationSignature" {}))))
            (let [email-token (api/email-token signing/subject)]
              (is (= 400 (:status (post full "signPlcOperation" {"token" "wrong"}))))
              (let [signed (post full "signPlcOperation" {"token" email-token}) operation (get-in signed [:json "operation"])]
                (is (= 200 (:status signed)))
                (is (= ["at://alicia.example.com"] (get operation "alsoKnownAs")))
                (is (= 403 (:status (post handle "submitPlcOperation" {"operation" operation}))))
                (is (= 200 (:status (post full "submitPlcOperation" {"operation" operation}))))
                (is (= (plc/operation-cid operation) (:operation_cid (signing/stored did)))))
              (is (= 400 (:status (post full "signPlcOperation" {"token" email-token}))))
              (is (= 3 (count (directory/posts calls)))))))))))
