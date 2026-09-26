(ns pds.identity-api-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.app :as app]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.identity :as identity]
            [pds.protocol.codec :as codec]
            [pds.server-api-test :as api])
  (:import [java.net.http HttpClient]))
(use-fixtures :each fixture/isolated-database)

(deftest hosted-and-remote-identity-routes
  (let [remote "did:plc:ewvi7nxzyoun6zhxrhs64oiz"
        key (crypto/keypair "ES256K")
        document (atom {"id" remote "alsoKnownAs" ["at://remote.example.com"]
                       "verificationMethod" [{"id" "#atproto" "type" "Multikey" "controller" remote
                                              "publicKeyMultibase" (crypto/multikey "ES256K" (:public key))}]
                       "service" [{"id" "#atproto_pds" "type" "AtprotoPersonalDataServer" "serviceEndpoint" "https://remote-pds.example.com"}]})
        outbound (atom [])
        settings (merge (api/settings)
                        {:plc-url "https://directory.example.com"
                         :txt-lookup (fn [name] (swap! outbound conj name) [(str "did=" remote)])
                         :fetch (fn [url _] (swap! outbound conj url)
                                  {:status 200 :body (codec/utf8 (json/write-str @document))})})
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
            (is (= {"did" remote "handle" "remote.example.com" "didDoc" @document} (:body resolved)))
            (is (= "ES256K" (:algorithm (identity/signing-key (get-in resolved [:body "didDoc"])))))
            (is (= "https://remote-pds.example.com" (identity/pds-endpoint (get-in resolved [:body "didDoc"])))))
          (is (some #{(str "https://directory.example.com/" remote)} @outbound))
          (swap! document assoc "alsoKnownAs" ["at://handle.invalid" "at://remote.example.com"])
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
