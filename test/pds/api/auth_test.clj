(ns pds.api.auth-test
  (:require [clojure.test :refer [deftest is]]
            [pds.api.auth :as auth]))

(deftest every-method-in-the-contract-is-routed
  (let [routes (auth/routes nil {})]
    (doseq [[nsid method]
            [["getTwoFactor" :get]
             ["beginTwoFactor" :post]
             ["confirmTwoFactor" :post]
             ["disableTwoFactor" :post]
             ["regenerateRecoveryCodes" :post]
             ["listPasskeys" :get]
             ["beginPasskeyRegistration" :post]
             ["finishPasskeyRegistration" :post]
             ["deletePasskey" :post]
             ["beginPasskeyLogin" :post]
             ["finishPasskeyLogin" :post]]]
      (let [route (get routes (str "/xrpc/social.rocksky.auth." nsid))]
        (is (some? route) (str nsid " is not routed"))
        (is (= method (:method route)) (str nsid " has the wrong method"))
        (is (fn? (:handler route)))))
    (is (= 11 (count routes)) "the contract has eleven methods")))

(deftest a_request_id_carries_both_halves_of_the_ceremony
  (let [encode #'auth/request-id
        decode #'auth/split-request-id
        id (apply str (repeat 43 "a"))
        browser (apply str (repeat 43 "b"))]
    (is (= [id browser] (decode (encode {:id id :browser browser}))))

    ;; Without both halves the ceremony cannot be claimed, so the id is refused
    ;; rather than passed to the passkey layer.
    (doseq [junk [nil "" "no-separator" (str "." browser) 42]]
      (is (thrown? Exception (decode junk)) (str "accepted " (pr-str junk))))

    ;; A secret containing the separator must still round-trip: only the first
    ;; separator divides the two halves.
    (is (= [id (str "b.c")] (decode (str id "." "b.c"))))))

(deftest app_passwords_cannot_reach_account_security
  (let [full-session! #'auth/full-session!]
    ;; An app password is issued to a client, so it is not the owner proving who
    ;; they are. It must not be able to add or remove a way of signing in.
    (is (thrown? Exception (full-session! {:did "did:web:a" :app_password_id 7})))
    (is (= {:did "did:web:a"} (full-session! {:did "did:web:a"})))))

(deftest an_invalid_proof_becomes_a_response_rather_than_being_ignored
  (let [raise-result! #'auth/raise-result!]
    ;; The factor functions return invalid proofs instead of throwing, so the
    ;; attempt accounting commits. Anything that forgets to raise would accept a
    ;; wrong code.
    (is (thrown? Exception (raise-result! {:error "InvalidToken" :status 401})))
    (is (thrown? Exception (raise-result! {:error "RateLimitExceeded" :status 429})))
    (is (= {:valid? true} (raise-result! {:valid? true})))))

(deftest a_passkey_view_omits_what_it_does_not_know
  (let [view #'auth/passkey-view]
    (is (= {"id" "abc"} (view {:id "abc"})))
    (is (= {"id" "abc" "name" "MacBook" "createdAt" "2026-01-01"}
           (view {:id "abc" :name "MacBook" :created-at "2026-01-01"})))
    ;; Internal fields the contract does not describe stay internal.
    (is (= {"id" "abc"} (view {:id "abc" :backup-eligible true :backed-up false})))))
