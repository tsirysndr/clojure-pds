(ns pds.oauth.scope-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [pds.oauth.scope :as scope]
            [pds.oauth.permissions :as permissions])
  (:import [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(def valid-scopes
  ["repo:com.example.note" "repo:*?action=delete" "repo:com.example.note?action=create&action=update"
   "repo?collection=com.example.note&collection=com.example.profile&action=update"
   "repo:com.example.%6Eote?%61ction=create" "repo:com.example.note?"
   "blob:image/*" "blob:*/*" "blob?accept=image/png&accept=video/*"
   "blob:application/ld+json" "blob?accept=application/ld%2Bjson"
   "rpc:com.example.getNote?aud=*" "rpc:*?aud=did:web:api.example.com%23appview"
   "rpc?lxm=com.example.getNote&lxm=com.example.getFeed&aud=did:web:api.example.com%23appview"
   "account:email" "account?attr=email&action=read" "account:email?action=manage"
   "account:repo?action=manage" "account:repo" "identity:handle" "identity:*" "identity?attr=*"])
(def invalid-scopes
  [nil "" "repo" "repo:" "repo:com.example.*" "repo:com.example.note?action=read" "repo:com.example.note?future=narrow"
   "repo:com.example.note?collection=com.example.other" "repo?collection=" "repo:com.example.note?action="
   "repo:com.example.note?%61ction=read" "repo:com.example.%ZZ" "repo:com.example.%FF"
   "blob" "blob:*/png" "blob:image/p*" "blob:im*age/*" "blob:application/ld+json?accept=image/png"
   "blob?accept=application/ld+json" "blob:image/png;evil" "blob:text/html?future=narrow"
   "rpc:*?aud=*" "rpc:com.example.read" "rpc:com.example.*?aud=*"
   "rpc:com.example.read?aud=did:web:api.example.com" "rpc:com.example.read?aud=*&aud=*"
   "rpc:com.example.read?aud=*&inheritAud=true" "rpc:com.example.read?aud=did:key:zFoo%23service"
   "rpc:com.example.read?aud=did:web:api.example.com:path%23service" "rpc:com.example.read?aud=did:web:api.example.com%253A8443%23service" "rpc:com.example.read?aud=did:web:api.example.com%23"
   "account:email?attr=email" "account:email?action=read&action=read" "account:*"
   "account:email?future=narrow" "identity:handle?action=read" "account:email?action=write" "account:status?action=manage"
   "include:com.example.permissions" "atproto?x=1" "Repo:com.example.note"])
(defn denied? [f] (try (f) false (catch clojure.lang.ExceptionInfo e (= "insufficient_scope" (:error (ex-data e))))))

(deftest direct-permission-syntax-is-strict-and-bounded
  (doseq [value valid-scopes] (is (some? (scope/parse value)) (str value)))
  (doseq [value invalid-scopes] (is (nil? (scope/parse value)) (str value)))
  (is (= (scope/parse "repo:com.example.note?action=create") (scope/parse "repo?collection=com.example.note&action=create")))
  (is (= #{"application/ld+json"} (:accept (scope/parse "blob:application/ld+json"))))
  (is (= #{"image/png"} (:accept (scope/parse "blob:IMAGE/PNG"))))
  (is (nil? (scope/parse (str "repo:" (apply str (repeat 8192 "a")))))))

(deftest least-privilege-matching-and-human-readable-consent
  (let [account {:oauth-scope "atproto repo:com.example.note?action=create blob:image/* rpc:chat.bsky.convo.getMessages?aud=did:web:chat.example.com%23chat account:email"}]
    (is (nil? (permissions/repo! account "com.example.note" :create)))
    (is (denied? #(permissions/repo! account "com.example.note" :update)))
    (is (denied? #(permissions/repo! account "com.example.other" :create)))
    (is (nil? (permissions/blob! account "image/png")))
    (is (denied? #(permissions/blob! account "video/mp4")))
    (is (nil? (permissions/rpc! account "chat.bsky.convo.getMessages" "did:web:chat.example.com#chat")))
    (is (denied? #(permissions/rpc! account "chat.bsky.convo.sendMessage" "did:web:chat.example.com#chat")))
    (is (denied? #(permissions/rpc! account "chat.bsky.convo.getMessages" "did:web:other.example.com#chat")))
    (is (permissions/email? account))
    (is (= ["Confirm your account identity." "Create public records in com.example.note."
            "Upload media of these types: image/*." "Call chat.bsky.convo.getMessages at did:web:chat.example.com#chat."
            "Read your email address and verification status."] (scope/describe (:oauth-scope account)))))
  (is (nil? (permissions/repo! {} "com.example.note" :delete)))
  (is (nil? (permissions/blob! {} "video/mp4")))
  (is (false? (boolean (permissions/email? {:oauth-scope "atproto repo:*"})))))

(when (= "true" (System/getenv "PDS_TEST_UPSTREAM"))
  (deftest pinned-upstream-parser-and-matcher-agree-on-supported-grants
    (let [path (Files/createTempFile "pds-scopes-" ".json" (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (spit (str path) (json/write-str (mapv (fn [value] {:scope value :permission (scope/parse value)}) valid-scopes)))
        (let [process (.start (doto (ProcessBuilder. ^java.util.List ["node" "scripts/conformance/verify-scopes.mjs" (str path)]) (.redirectErrorStream true)))
              finished (.waitFor process 30 TimeUnit/SECONDS)]
          (when-not finished (.destroyForcibly process))
          (is finished)
          (when finished (is (zero? (.exitValue process)) (slurp (.getInputStream process)))))
        (finally (Files/deleteIfExists path))))))
