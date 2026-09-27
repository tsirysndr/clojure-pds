(ns pds.dynamic-record-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.lexicon-resolver :as resolver]
            [pds.lexicon-resolver-test :as publisher]
            [pds.oauth-permissions-test :as permissions]
            [pds.oauth-resource-test :as resource]
            [pds.oauth-tokens-test :as token]
            [pds.record-proof-test :as proofs]
            [pds.record-validation :as validation]
            [pds.repo :as repo]
            [pds.repository-test :as repository]
            [pds.server-api-test :as api])
  (:import [java.net URI URLDecoder]
           [java.net.http HttpClient]))

(use-fixtures :each fixture/isolated-database)
(def collection "com.example.note")
(def dependency "net.publisher.defs")
(def schema {"$type" "com.atproto.lexicon.schema" "lexicon" 1 "id" collection
             "defs" {"main" {"type" "record" "key" "any" "record"
                             {"type" "object" "required" ["text"]
                              "properties" {"text" {"type" "ref" "ref" (str dependency "#text")}}}}}})
(defn env []
  (let [publisher (publisher/fixture) calls (atom [])
        records {(str "com.atproto.lexicon.schema/" collection) schema
                 (str "com.atproto.lexicon.schema/" dependency)
                 {"$type" "com.atproto.lexicon.schema" "lexicon" 1 "id" dependency
                  "defs" {"text" {"type" "string" "maxLength" 8}}}}
        repo (repository/fixture (:key publisher) records)
        remote (assoc (:resolver publisher) :fetch
                      (fn [url _]
                        (swap! calls conj url)
                        (let [nsid (URLDecoder/decode (second (re-find #"rkey=([^&]+)" (.getRawQuery (URI/create url)))) "UTF-8")]
                          {:status 200 :body (proofs/slice repo (str "com.atproto.lexicon.schema/" nsid))})))
        cache (validation/cache #(:schema (resolver/resolve! remote %)))]
    {:cache cache :calls calls :remote remote :settings (assoc (api/settings) :record-schema-cache cache)}))
(defn signup [settings]
  (accounts/create! fixture/*ds* settings {"handle" "schema.example.com" "email" "schema@example.com" "password" "test-password"}))
(defn body [did key text]
  {"repo" did "collection" collection "rkey" key "validate" true "record" {"$type" collection "text" text}})
(defn snapshot []
  [(token/query "SELECT head, rev FROM repositories ORDER BY did")
   (token/query "SELECT count(*) AS n FROM records")
   (token/query "SELECT count(*) AS n FROM repo_blocks")
   (token/query "SELECT count(*) AS n FROM repo_events")])

(deftest signed-schemas-validate-records-over-http
  (let [{:keys [settings calls]} (env) account (signup settings) did (:did account)
        server (http/start! settings (app/handler settings fixture/*ds*))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [call #(api/xrpc client (:port server) "POST" (str "com.atproto.repo." %1) %2 (:accessJwt account))]
          (is (= "unknown" (get-in (call "createRecord" (dissoc (body did "unknown" "too long initially") "validate")) [:body "validationStatus"])))
          (is (empty? @calls))
          (is (= "valid" (get-in (call "createRecord" (body did "valid" "hello")) [:body "validationStatus"])))
          (is (= 2 (count @calls)) "The root and foreign dependency both require signed proofs")
          (is (= "valid" (get-in (call "putRecord" (dissoc (body did "valid" "changed") "validate")) [:body "validationStatus"])))
          (let [before (snapshot)]
            (is (= 400 (:status (call "putRecord" (body did "valid" "too long now")))))
            (is (= 400 (:status (call "createRecord" (dissoc (body did "invalid" "too long now") "validate")))))
            (is (= before (snapshot))))
          (let [before (snapshot)
                entry (fn [key text] {"$type" "com.atproto.repo.applyWrites#create" "collection" collection
                                     "rkey" key "value" {"$type" collection "text" text}})]
            (is (= 400 (:status (call "applyWrites" {"repo" did "validate" true "writes" [(entry "first" "good") (entry "second" "way too long")]}))))
            (is (= before (snapshot)) "A schema failure rolls back all records, blocks, head and events")
            (is (= ["valid" "valid"]
                   (mapv #(get % "validationStatus") (get-in (call "applyWrites" {"repo" did "validate" true "writes" [(entry "first" "one") (entry "second" "two")]}) [:body "results"])))))
          (is (= 200 (:status (call "putRecord" (assoc (body did "valid" "too long but skipped") "validate" false)))))
          (is (= 2 (count @calls)))))
      (finally ((:stop! server))))))

(deftest resolution-runs-unlocked-and-legacy-revocation-wins
  (let [{:keys [settings cache calls]} (env) account (signup settings)
        lookup (:lookup cache) ds fixture/*ds*
        cache (assoc cache :lookup (fn [id]
                                    (db/transact! ds
                                      (fn [conn]
                                        (db/execute! conn "SET LOCAL lock_timeout = '500ms'")
                                        (db/query conn "SELECT did FROM accounts WHERE did = ? FOR UPDATE" (:did account))
                                        (db/execute! conn "UPDATE sessions SET revoked = true WHERE did = ?" (:did account))))
                                    (lookup id)))
        settings (assoc settings :record-schema-cache cache)
        server (http/start! settings (app/handler settings fixture/*ds*)) before (snapshot)]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (is (= 401 (:status (api/xrpc client (:port server) "POST" "com.atproto.repo.createRecord"
                                    (body (:did account) "denied" "hello") (:accessJwt account)))))
        (is (= 2 (count @calls)))
        (is (= before (snapshot))))
      (finally ((:stop! server))))))

(deftest oauth-scope-denial-precedes-resolution-and-revocation-is-rechecked
  (let [{:keys [cache calls]} (env) env (token/env)
        denied (permissions/mint! env "atproto repo:com.other.note?action=create")
        issued (permissions/mint! env "atproto repo:com.example.note?action=create")
        lookup (:lookup cache)
        cache (assoc cache :lookup (fn [id]
                                    (db/transact! fixture/*ds*
                                      (fn [conn]
                                        (db/execute! conn "SET LOCAL lock_timeout = '500ms'")
                                        (db/query conn "SELECT did FROM accounts FOR UPDATE")
                                        (db/execute! conn "UPDATE oauth_sessions SET revoked_at = now()")))
                                    (lookup id)))
        handler (app/handler (assoc (resource/settings env) :record-schema-cache cache) fixture/*ds*)
        record (assoc (permissions/note "dynamic") "validate" true)
        before (snapshot)]
    (is (= 403 (:status (permissions/call! handler env denied "createRecord" record))))
    (is (empty? @calls))
    (is (= 401 (:status (permissions/call! handler env issued "createRecord" record))))
    (is (= 2 (count @calls)))
    (is (= before (snapshot)))))

(deftest malformed-proof-fails-explicit-validation-without-changing-repository
  (let [{:keys [settings remote]} (env) attempts (atom 0)
        remote (assoc remote :fetch (fn [_ _] (swap! attempts inc) {:status 200 :body (byte-array [1 2 3])}))
        cache (validation/cache #(:schema (resolver/resolve! remote %)))
        settings (assoc settings :record-schema-cache cache) account (signup settings)
        server (http/start! settings (app/handler settings fixture/*ds*)) before (snapshot)]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [call (fn [body token] (api/xrpc client (:port server) "POST" "com.atproto.repo.createRecord" body token))
              record (body (:did account) "proof" "hello")]
          (is (= 401 (:status (call record nil))))
          (is (= 400 (:status (call (assoc-in record ["record" "text"] 1.5) (:accessJwt account)))))
          (is (zero? @attempts))
          (dotimes [_ 2]
            (let [result (call record (:accessJwt account))]
              (is (= 400 (:status result)))
              (is (= "InvalidRecord" (get-in result [:body "error"])))))
          (is (= 1 @attempts) "Invalid proofs are never cached as schemas; failures have a retry cooldown")
          (is (= before (snapshot)))
          (is (= "unknown" (get-in (call (dissoc record "validate") (:accessJwt account)) [:body "validationStatus"])))
          (is (= 1 @attempts))))
      (finally ((:stop! server))))))

(deftest put-action-is-rechecked-after-schema-resolution
  (let [{:keys [cache]} (env) env (token/env)
        issued (permissions/mint! env "atproto repo:com.example.note?action=create")
        record (assoc (permissions/note "raced") "validate" true "record" {"$type" collection "text" "client"})
        lookup (:lookup cache) changed (atom false)
        cache (assoc cache :lookup (fn [id]
                                    (when (compare-and-set! changed false true)
                                      (db/transact! fixture/*ds*
                                        (fn [conn]
                                          (db/execute! conn "SET LOCAL lock_timeout = '500ms'")
                                          (db/query conn "SELECT did FROM accounts FOR UPDATE")
                                          (repo/apply-writes! conn (:settings env) (get record "repo")
                                                             [{:action :create :collection collection :rkey "raced" :validate false
                                                               :value {"$type" collection "text" "other"}}] nil))))
                                    (lookup id)))
        handler (app/handler (assoc (resource/settings env) :record-schema-cache cache) fixture/*ds*)]
    (is (= 403 (:status (permissions/call! handler env issued "putRecord" record))))
    (is @changed)
    (with-open [conn (db/connection fixture/*ds*)]
      (is (= "other" (get-in (repo/record conn (get record "repo") collection "raced") [:value "text"]))))))
