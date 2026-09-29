(ns pds.client-interop-test
  "Interoperability with the official AT Protocol client and firehose consumer.
  A real HTTP server is started and driven by the pinned upstream packages, so
  request shaping, response Lexicon validation, blob encoding, error shapes and
  event framing are checked by their implementation rather than ours.

  Gated on PDS_TEST_UPSTREAM because it needs the Node packages installed by
  scripts/test-conformance.sh. This exercises deployed-client compatibility on
  a local server; it does not establish live-network federation."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.app :as app]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.plc :as plc]
            [pds.server-api-test :as api])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]))

(use-fixtures :each fixture/isolated-database)

(def upstream? (= "true" (System/getenv "PDS_TEST_UPSTREAM")))

(defn run-script!
  "Run one pinned upstream script against a fixture and return its JSON output."
  [script value]
  (let [path (Files/createTempFile "pds-interop-" ".json" (make-array FileAttribute 0))]
    (try
      (spit (.toFile path) (json/write-str value))
      (let [process (.start (doto (ProcessBuilder. ^java.util.List ["node" (str "scripts/conformance/" script) (str path)])
                              (.redirectErrorStream true)))
            finished? (.waitFor process 120 TimeUnit/SECONDS)]
        (when-not finished? (.destroyForcibly process))
        (is finished? (str script " must finish within 120 seconds"))
        (when finished?
          (let [output (slurp (.getInputStream process))]
            (is (zero? (.exitValue process)) output)
            ;; The scripts end with their JSON summary on the final line.
            (when (zero? (.exitValue process))
              (json/read-str (last (remove str/blank? (str/split-lines output))))))))
      (finally (Files/deleteIfExists path)))))

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)] (.getLocalPort socket)))

(defn- local-settings
  "The client follows the PDS endpoint advertised in the DID document, so the
  public URL has to be the address this server actually binds."
  []
  (let [port (free-port)]
    (assoc (api/settings) :port port :signup-enabled true
           :public-url (str "http://127.0.0.1:" port))))

(defn- with-server [settings f]
  (let [server (http/start! settings (app/handler settings fixture/*ds*))]
    (try (f (:public-url settings)) (finally ((:stop! server))))))

(deftest official-client-completes-an-account-lifecycle
  (when upstream?
    (let [settings (local-settings)]
      (with-server settings
        (fn [service]
          (let [result (run-script! "verify-client.mjs"
                                    {:service service
                                     :handle "alice.example.com"
                                     :email "alice@example.com"
                                     :password "correct-horse-battery"})]
            (is (= "did:web:alice.example.com" (get result "did")))
            (is (= (:service-did settings) (get result "serverDid")))
            (is (= 3 (get result "created")))
            (is (pos? (get result "carBytes")))
            ;; The client uploaded and read back a blob through our own storage.
            (is (= 1 (:n (first (with-open [conn (db/connection fixture/*ds*)]
                                  (db/query conn "SELECT count(*) AS n FROM blobs WHERE cid = ?"
                                            (get result "blobCid")))))))))))))

(deftest official-firehose-consumer-validates-our-event-stream
  (when upstream?
    (let [settings (local-settings)]
      (with-server settings
        (fn [service]
          ;; The client's writes produce the commits the consumer then reads
          ;; from sequence zero, so both sides use the same live server.
          (run-script! "verify-client.mjs"
                       {:service service
                        :handle "alice.example.com"
                        :email "alice@example.com"
                        :password "correct-horse-battery"})
          (let [did "did:web:alice.example.com"
                key (with-open [conn (db/connection fixture/*ds*)]
                      (:public_key (first (db/query conn "SELECT public_key FROM repositories WHERE did = ?" did))))
                result (run-script! "verify-firehose.mjs"
                                    {:service service :did did :handle "alice.example.com"
                                     :signingKey (plc/did-key {:algorithm "ES256" :public key})
                                     :expect 4})
                events (get result "events")]
            (is (seq events))
            (is (some #(and (= "create" (get % "event")) (= "app.bsky.feed.post" (get % "collection"))) events))
            (is (some #(= "delete" (get % "event")) events))
            (is (some #(= "app.bsky.actor.profile" (get % "collection")) events))))))))
