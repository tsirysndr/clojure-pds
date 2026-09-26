(ns pds.oauth.par-test
  (:require [clojure.test :refer [deftest is]]
            [pds.crypto :as crypto]
            [pds.oauth.client-test :as client]
            [pds.oauth.dpop-test :as proof]
            [pds.oauth.par :as par]
            [pds.oauth.pkce :as pkce]))

(def verifier "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
(def challenge "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
(defn params [] {"client_id" client/client-id "response_type" "code" "redirect_uri" client/redirect
                "scope" "atproto transition:generic" "state" (crypto/token)
                "code_challenge" (crypto/digest-token (crypto/token)) "code_challenge_method" "S256"})

(deftest rfc7636-s256-vector-and-verifier-boundaries
  (is (= challenge (crypto/digest-token verifier)))
  (is (true? (pkce/verify! challenge verifier)))
  (is (= challenge (pkce/challenge! challenge "S256")))
  (doseq [v [(apply str (repeat 43 "a")) (apply str (repeat 128 "~"))]]
    (is (pkce/verify! (crypto/digest-token v) v)))
  (doseq [v [nil "" (apply str (repeat 42 "a")) (apply str (repeat 129 "a"))
             (str verifier "=") (str verifier "+") (str verifier "/") (str verifier " ")
             (str "wrong" verifier)]]
    (is (= "invalid_grant" (proof/error #(pkce/verify! challenge v)))))
  (doseq [c [nil "" (str challenge "=") (subs challenge 1) (str (subs challenge 0 42) "N")]]
    (is (= "invalid_request" (proof/error #(pkce/challenge! c "S256")))))
  (doseq [method [nil "plain" "s256"]]
    (is (= "invalid_request" (proof/error #(pkce/challenge! challenge method))))))

(deftest par-parameters-are-bound-and-credential-fields-are-not-persisted
  (let [resolved (client/resolve-value (client/metadata)) input (params)
        approved (par/parameters! resolved (assoc input "client_assertion" "sensitive" "arbitrary" "discard" "login_hint" "alice.example.com"))]
    (is (= (get input "state") (get approved "state")))
    (is (= "alice.example.com" (get approved "login_hint")))
    (is (= "create" (get (par/parameters! resolved (assoc input "prompt" "create")) "prompt")))
    (is (not (contains? approved "client_assertion")))
    (is (not (contains? approved "client_id")))
    (is (not (contains? approved "arbitrary")))
    (doseq [field ["state" "redirect_uri" "code_challenge" "code_challenge_method"]]
      (is (= "invalid_request" (proof/error #(par/parameters! resolved (dissoc input field))))))
    (doseq [[field value] [["state" ""] ["state" (apply str (repeat 1025 "x"))]
                           ["redirect_uri" "https://evil.example.com/"] ["code_challenge_method" "plain"]
                           ["request" "nested"] ["request_uri" "nested"] ["code_verifier" verifier]
                           ["response_mode" "fragment"] ["prompt" "none"] ["login_hint" 7]]]
      (is (= "invalid_request" (proof/error #(par/parameters! resolved (assoc input field value))))))
    (is (= "unsupported_response_type" (proof/error #(par/parameters! resolved (assoc input "response_type" "token")))))
    (doseq [scope [nil "" "transition:generic" "atproto transition:email" "atproto repo:app.bsky.feed.post"]]
      (is (= "invalid_scope" (proof/error #(par/parameters! resolved (assoc input "scope" scope))))))
    (let [all-scopes (client/resolve-value (assoc (client/metadata) "scope" "atproto transition:generic transition:email transition:chat.bsky"))]
      (is (= "invalid_scope" (proof/error #(par/parameters! all-scopes (assoc input "scope" "atproto transition:chat.bsky")))))
      (is (map? (par/parameters! all-scopes (assoc input "scope" "atproto transition:generic transition:chat.bsky")))))))
