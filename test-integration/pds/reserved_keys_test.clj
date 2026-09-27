(ns pds.reserved-keys-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.app :as app]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.identity :as identity]
            [pds.migration :as migration]
            [pds.migration-test :as migration-test]
            [pds.plc :as plc]
            [pds.plc-directory-test :as directory-test]
            [pds.plc-provision-test :as provision :refer [rows]]
            [pds.reserved-keys :as reserved-keys]
            [pds.server-api-test :as api]
            [pds.service-auth-test :refer [error token-request]])
  (:import [java.net.http HttpClient]))

(use-fixtures :each fixture/isolated-database)

(deftest reservations-validate-repeat-and-are-consumed-by-destination-creation
  (directory-test/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin)
            source (migration-test/source! client origin) did (:did source)
            server (http/start! settings (app/handler settings fixture/*ds*))]
        (try
          (with-open [http (HttpClient/newHttpClient)]
            (let [call #(api/xrpc http (:port server) %1 %2 %3 %4)
                  reserve #(call "POST" "com.atproto.server.reserveSigningKey" % nil)]
              (is (= 400 (:status (reserve {"did" "did:example:unsupported"}))))
              (is (= 400 (:status (reserve {"did" 7}))))
              (let [anonymous (get-in (reserve {}) [:body "signingKey"])]
                (is (str/starts-with? anonymous "did:key:z"))
                (is (= "ES256" (:algorithm (plc/parse-key anonymous)))))
              (let [reserved (get-in (reserve {"did" did}) [:body "signingKey"])]
                (is (= reserved (get-in (reserve {"did" did}) [:body "signingKey"])))
                (is (= 2 (count (rows "SELECT 1 FROM reserved_signing_keys"))))
                (is (empty? (rows "SELECT 1 FROM reserved_signing_keys WHERE signing_key = public_key")))
                (let [token (migration-test/authorization settings source)
                      response (call "POST" "com.atproto.server.createAccount" (migration-test/input source) token)
                      repo (first (rows "SELECT public_key FROM repositories WHERE did = ?" did))]
                  (is (= 200 (:status response)))
                  (is (= reserved (plc/did-key {:algorithm "ES256" :public (:public_key repo)})))
                  (is (empty? (rows "SELECT 1 FROM reserved_signing_keys WHERE did = ?" did)))
                  (let [credentials (:body (call "GET" "com.atproto.identity.getRecommendedDidCredentials"
                                                 nil (get-in response [:body "accessJwt"])))]
                    (is (= reserved (get-in credentials ["verificationMethods" "atproto"]))))))))
          (finally ((:stop! server))))))))

(deftest reservations-expire-bound-capacity-and-key-destination-fallback
  (directory-test/with-directory
    (fn [{:keys [client origin]}]
      (let [settings (provision/settings client origin)
            source (migration-test/source! client origin) did (:did source)
            reserve #(reserved-keys/reserve! fixture/*ds* settings %)]
        (let [stale (:signingKey (reserve {"did" did}))]
          (db/transact! fixture/*ds* #(db/execute! % "UPDATE reserved_signing_keys SET created_at = now() - interval '25 hours'"))
          (let [fresh (:signingKey (reserve {"did" did}))]
            (is (not= stale fresh))
            (is (= 1 (count (rows "SELECT 1 FROM reserved_signing_keys"))))
            (with-redefs [reserved-keys/capacity 1]
              (is (= "ReservationBusy" (error #(reserve {}))))
              (is (= fresh (:signingKey (reserve {"did" did}))) "Existing reservations survive a full table"))
            (db/transact! fixture/*ds* #(db/execute! % "DELETE FROM reserved_signing_keys"))
            (let [request (token-request (migration-test/authorization settings source))
                  result (migration/create! fixture/*ds* settings (identity/resolver settings) request (migration-test/input source))
                  repo (first (rows "SELECT public_key FROM repositories WHERE did = ?" did))]
              (is (= did (:did result)))
              (is (not= fresh (plc/did-key {:algorithm "ES256" :public (:public_key repo)}))
                  "Without a reservation the destination generates a fresh key"))))))))
