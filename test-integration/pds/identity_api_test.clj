(ns pds.identity-api-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.auth :as auth]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.identity :as identity]
            [pds.plc :as plc]
            [pds.plc-directory :as directory]
            [pds.plc-directory-test :as directory-test]
            [pds.plc-provision-test :as provision-test]
            [pds.plc-test :as plc-test]
            [pds.protocol.codec :as codec]
            [pds.service-auth :as service-auth]
            [pds.service-auth-test :as service-test]
            [pds.server-api-test :as api])
  (:import [java.net.http HttpClient]))
(use-fixtures :each fixture/isolated-database)

(deftest hosted-and-remote-identity-routes
  (let [key (crypto/keypair "ES256K")
        genesis (plc/sign-operation (assoc (plc-test/unsigned [key] key)
                                          "alsoKnownAs" ["at://remote.example.com"]
                                          "services" {"atproto_pds" {"type" "AtprotoPersonalDataServer" "endpoint" "https://remote-pds.example.com"}}) key)
        remote (plc/genesis-did genesis)
        document (plc/did-document (plc/operation-data remote genesis))
        audit (atom [(plc-test/row remote genesis "2026-01-01T00:00:00Z" false)])
        outbound (atom [])
        settings (merge (api/settings)
                        {:plc-url "https://directory.example.com"
                         :txt-lookup (fn [name] (swap! outbound conj name) [(str "did=" remote)])
                         :fetch (fn [url _] (swap! outbound conj url)
                                  {:status 200 :body (codec/utf8 (json/write-str @audit))})})
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [call #(api/xrpc client (:port server) %1 %2 %3 nil)
              account (:body (call "POST" "com.atproto.server.createAccount"
                                   {"handle" "alice.example.com" "email" "alice@example.com" "password" "signup-password"}))
              did (get account "did")
              local (:body (call "GET" (str "com.atproto.identity.resolveIdentity?identifier=" did) nil))]
          (is (= "did:web:alice.example.com" did))
          (is (= did (get local "did")))
          (is (= "alice.example.com" (get local "handle")))
          (is (= (get account "didDoc") (get local "didDoc")))
          (is (= did (get-in (call "GET" "com.atproto.identity.resolveHandle?handle=ALICE.example.com" nil) [:body "did"])))
          (is (= (get account "didDoc") (get-in (call "GET" (str "com.atproto.identity.resolveDid?did=" did) nil) [:body "didDoc"])))
          (is (= local (:body (call "POST" "com.atproto.identity.refreshIdentity" {"identifier" "alice.example.com"}))))
          (is (= "did:web:pds.example.com" (get-in (call "GET" "com.atproto.identity.resolveDid?did=did:web:pds.example.com" nil) [:body "didDoc" "id"])))
          (is (empty? @outbound) "Hosted identities and the service document use PostgreSQL without network calls")
          (let [resolved (call "GET" "com.atproto.identity.resolveIdentity?identifier=remote.example.com" nil)]
            (is (= 200 (:status resolved)))
            (is (= {"did" remote "handle" "remote.example.com" "didDoc" document} (:body resolved)))
            (is (= "ES256K" (:algorithm (identity/signing-key (get-in resolved [:body "didDoc"])))))
            (is (= "https://remote-pds.example.com" (identity/pds-endpoint (get-in resolved [:body "didDoc"])))))
          (let [before @outbound]
            (is (= 200 (:status (call "GET" "com.atproto.identity.resolveIdentity?identifier=remote.example.com" nil))))
            (is (= before @outbound) "Repeated resolution uses cached remote bindings and verified documents"))
          (is (some #{(str "https://directory.example.com/" remote "/log/audit")} @outbound))
          (swap! audit conj (plc-test/row remote (plc-test/update-op genesis key {"alsoKnownAs" ["at://handle.invalid" "at://remote.example.com"]})
                                        "2026-01-01T01:00:00Z" false))
          (is (= "handle.invalid" (get-in (call "POST" "com.atproto.identity.refreshIdentity" {"identifier" remote}) [:body "handle"])))
          (doseq [endpoint ["com.atproto.identity.resolveHandle" "com.atproto.identity.resolveHandle?handle=alice.example.com&handle=bob.example.com"
                            "com.atproto.identity.resolveDid?did=bad" "com.atproto.identity.resolveIdentity?identifier=bad"]]
            (is (= 400 (:status (call "GET" endpoint nil)))))
          (is (= 405 (:status (call "POST" "com.atproto.identity.resolveHandle" {"handle" "alice.example.com"}))))
          (is (= 400 (:status (call "POST" "com.atproto.identity.refreshIdentity" {}))))
          (with-open [conn (db/connection fixture/*ds*)]
            (db/execute! conn "UPDATE accounts SET status = 'deleted' WHERE did = ?" did))
          (let [before @outbound]
            (is (= "DidDeactivated" (get-in (call "GET" (str "com.atproto.identity.resolveDid?did=" did) nil) [:body "error"])))
            (is (= "HandleNotFound" (get-in (call "GET" "com.atproto.identity.resolveHandle?handle=alice.example.com" nil) [:body "error"])))
            (is (= before @outbound) "Deleted hosted identities cannot fall through to outbound resolution"))))
      (finally ((:stop! server))))))

(deftest hosted-plc-resolution-observes-external-migration-and-fails-closed
  (directory-test/with-directory
    (fn [{:keys [client origin audit-body]}]
      (let [settings (provision-test/settings client origin)
            account (accounts/create! fixture/*ds* settings (provision-test/signup)) did (:did account)
            row (first (provision-test/rows "SELECT * FROM plc_identities WHERE did = ?" did))
            genesis (codec/decode (:operation row))
            signer {:algorithm "ES256K" :private (crypto/unseal (:master-key settings) (str did ":plc-rotation") (:rotation_key row))}
            replacement (crypto/keypair "ES256")
            moved (plc-test/update-op genesis signer
                    {"alsoKnownAs" ["at://moved.example.net"]
                     "verificationMethods" {"atproto" (plc/did-key replacement)}
                     "services" {"atproto_pds" {"type" "AtprotoPersonalDataServer" "endpoint" "https://destination.example.net"}}})
            server (http/start! (assoc settings :txt-lookup (fn [_] [(str "did=" did)]))
                                (app/handler (assoc settings :txt-lookup (fn [_] [(str "did=" did)])) fixture/*ds*))]
        (try
          (directory/ensure-operation! client origin did moved)
          (with-open [http (HttpClient/newHttpClient)]
            (let [resolve #(api/xrpc http (:port server) "GET" (str "com.atproto.identity.resolveDid?did=" did) nil nil)
                  response (resolve) document (get-in response [:body "didDoc"])]
              (is (= 200 (:status response)))
              (is (= (plc/did-document (plc/operation-data did moved)) document))
              (is (= "https://destination.example.net" (identity/pds-endpoint document)))
              (is (= (vec (:public replacement)) (vec (:public (identity/signing-key document)))))
              (is (= (:operation_cid row) (:operation_cid (first (provision-test/rows "SELECT operation_cid FROM plc_identities WHERE did = ?" did))))
                  "Resolving current remote state does not silently mutate local keys")
              (is (= "moved.example.net" (get-in (api/xrpc http (:port server) "POST" "com.atproto.identity.refreshIdentity"
                                                                 {"identifier" did} nil) [:body "handle"])))
              (reset! audit-body (json/write-str (plc/did-document (plc/operation-data did genesis))))
              (is (= "DidResolutionFailed" (get-in (api/xrpc http (:port server) "POST" "com.atproto.identity.refreshIdentity"
                                                             {"identifier" did} nil) [:body "error"]))
                  "Refresh rejects an invalid audit and invalidates the cached document")
              (is (= "DidResolutionFailed" (get-in (resolve) [:body "error"])) "Invalid audit cannot fall back to a local or cached snapshot")
              (reset! audit-body nil)
              (directory/ensure-operation! client origin did (plc-test/tombstone moved signer))
              (is (= "DidDeactivated" (get-in (resolve) [:body "error"])))))
          (finally ((:stop! server))))))))

(deftest public-cache-disable-refresh-and-failure-behavior
  (doseq [ttl [0 300000]]
    (let [did "did:web:remote.example.net" calls (atom 0)
          response (atom {:status 200 :body (codec/utf8 (json/write-str {"id" did "version" 1}))})
          settings (assoc (api/settings) :identity-cache-ttl-ms ttl
                          :fetch (fn [_ _] (swap! calls inc) @response))
          server (http/start! settings (app/handler settings fixture/*ds*))]
      (try
        (with-open [client (HttpClient/newHttpClient)]
          (let [resolve #(api/xrpc client (:port server) "GET" (str "com.atproto.identity.resolveDid?did=" did) nil nil)
                refresh #(api/xrpc client (:port server) "POST" "com.atproto.identity.refreshIdentity" {"identifier" did} nil)]
            (is (= 1 (get-in (resolve) [:body "didDoc" "version"])))
            (reset! response {:status 200 :body (codec/utf8 (json/write-str {"id" did "version" 2}))})
            (is (= (if (zero? ttl) 2 1) (get-in (resolve) [:body "didDoc" "version"])))
            (is (= (if (zero? ttl) 2 1) @calls))
            (is (= 2 (get-in (refresh) [:body "didDoc" "version"])))
            (is (= 2 (get-in (resolve) [:body "didDoc" "version"])))
            (reset! response {:status 503})
            (is (= "DidResolutionFailed" (get-in (refresh) [:body "error"])))
            (is (= "DidResolutionFailed" (get-in (resolve) [:body "error"])))
            (reset! response {:status 200 :body (codec/utf8 (json/write-str {"id" did "version" 3}))})
            (is (= 3 (get-in (resolve) [:body "didDoc" "version"])))))
        (finally ((:stop! server)))))))

(deftest public-cached-keys-do-not-authorize-migration-service-tokens
  (let [did "did:web:source.example.net" old (crypto/keypair "ES256") new (crypto/keypair "ES256")
        document (atom (service-test/document did old)) calls (atom 0)
        settings (assoc (api/settings) :fetch (fn [_ _] (swap! calls inc) {:status 200 :body (codec/utf8 (json/write-str @document))}))
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [resolve #(api/xrpc client (:port server) "GET" (str "com.atproto.identity.resolveDid?did=" did) nil nil)
              token (fn [key] (let [now (auth/now)]
                                (service-auth/sign key did (:service-did settings) "com.atproto.server.createAccount" now (+ now 60))))
              create #(api/xrpc client (:port server) "POST" "com.atproto.server.createAccount"
                                {"did" did "handle" "migrated.example.com" "email" "migrated@example.com" "password" "migration-password"} (token %))]
          (is (= @document (get-in (resolve) [:body "didDoc"])))
          (reset! document (service-test/document did new))
          (is (= (service-test/document did old) (get-in (resolve) [:body "didDoc"])))
          (is (= 1 @calls))
          (is (= "BadJwtSignature" (get-in (create old) [:body "error"])))
          (is (= 2 @calls) "Migration verifies the current key rather than the public cached key")
          (is (= 200 (:status (create new))))
          (is (= 3 @calls))))
      (finally ((:stop! server))))))
