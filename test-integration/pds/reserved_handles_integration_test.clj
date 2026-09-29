(ns pds.reserved-handles-integration-test
  "Reserved first labels are refused on the self-service signup and handle-claim
  paths, while the operator endpoint can still register them deliberately."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.handles :as handles]
            [pds.handles-test :refer [current-handle request]]
            [pds.reserved-handles :as reserved]
            [pds.server-api-test :as api]))

(use-fixtures :each fixture/isolated-database)

(defn- settings []
  (merge (api/settings) (reserved/settings {"PDS_RESERVED_HANDLES" "admin,www"})))

(defn- signup [name]
  {"handle" (str name ".example.com") "email" (str name "@example.com")
   "password" "signup-password"})

(defn- error [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))

(defn- accounts-count []
  (with-open [conn (db/connection fixture/*ds*)]
    (:n (first (db/query conn "SELECT count(*) AS n FROM accounts")))))

(deftest signup-refuses-reserved-labels
  (let [settings (settings)]
    (doseq [name ["admin" "www"]]
      (is (= "HandleNotAvailable" (error #(accounts/create! fixture/*ds* settings (signup name)))) name))
    (is (zero? (accounts-count)) "A refused signup leaves no account behind")
    (is (string? (:did (accounts/create! fixture/*ds* settings (signup "alice")))))
    (is (= "HandleNotAvailable" (error #(accounts/create! fixture/*ds* settings (signup "ADMIN"))))
        "Reservation is case insensitive")))

(deftest owner-cannot-claim-a-reserved-label-but-the-operator-can
  (let [settings (settings)
        alice (accounts/create! fixture/*ds* settings (signup "alice"))]
    (is (= "HandleNotAvailable"
           (error #(handles/update! fixture/*ds* settings (request alice) {"handle" "admin.example.com"}))))
    (is (= "alice.example.com" (current-handle (:did alice))))
    (handles/admin-update! fixture/*ds* settings {"did" (:did alice) "handle" "admin.example.com"})
    (is (= "admin.example.com" (current-handle (:did alice)))
        "The operator endpoint may register a reserved label")
    (handles/update! fixture/*ds* settings (request alice) {"handle" "admin.example.com"})
    (is (= "admin.example.com" (current-handle (:did alice)))
        "An account already holding a reserved handle keeps it")))

(deftest an-empty-list-disables-reservation
  (let [settings (merge (api/settings) (reserved/settings {"PDS_RESERVED_HANDLES" ""}))]
    (is (string? (:did (accounts/create! fixture/*ds* settings (signup "admin")))))))
