(ns pds.oauth.permission-sets-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [pds.oauth.permission-sets :as sets]
            [pds.oauth.scope :as scope])
  (:import [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(def nsid "com.example.feed.authBasic")
(def include (str "include:" nsid))
(def audience "did:web:api.example.com#appview")
(def include-aud (str include "?aud=did:web:api.example.com%23appview"))
(defn schema [permissions]
  {"$type" "com.atproto.lexicon.schema" "lexicon" 1 "id" nsid
   "defs" {"main" {"type" "permission-set" "permissions" permissions "title" "Feeds"
                   "title:lang" {"fr" "Fils" "pt-BR" "Feeds brasileiros"} "detail" "Create posts and read feeds."}}})
(def repo {"type" "permission" "resource" "repo" "collection" ["com.example.feed.post" "com.example.feed.deep.reply"]})
(def rpc {"type" "permission" "resource" "rpc" "lxm" ["com.example.feed.getFeed"] "aud" "*"})
(def inherited (-> rpc (dissoc "aud") (assoc "inheritAud" true)))
(def invalid-declarations
  [(assoc repo "collection" ["com.example.feed.post" "com.example.actor.profile"])
   (assoc repo "collection" ["com.example.feedBad.post"]) (assoc repo "collection" ["com.example.parent"])
   (assoc repo "collection" ["*"]) (assoc repo "collection" ["com.example.feed.*"])
   (assoc repo "collection" []) (assoc repo "collection" "com.example.feed.post")
   (assoc repo "action" []) (assoc repo "action" ["create" "future"])
   (assoc repo "futureAttenuation" true) (assoc repo "type" "other")
   (assoc rpc "lxm" ["*"]) (assoc rpc "lxm" ["com.example.feed.getFeed" "com.example.other.getFeed"])
   (assoc rpc "aud" audience) (assoc inherited "aud" "*")
   (assoc inherited "inheritAud" "true") (assoc rpc "inheritAud" nil)
   {"type" "permission" "resource" "blob" "accept" ["*/*"]}
   {"type" "permission" "resource" "identity" "attr" "*"}
   {"type" "permission" "resource" "account" "attr" "email" "action" "manage"}
   {"type" "permission" "resource" "include" "nsid" "com.example.other"}
   {"type" "permission" "resource" "future"} nil [] "permission"])

(deftest include-syntax-and-admission-remain-separate
  (doseq [value [include include-aud (str "include?nsid=" nsid) (str "include:" nsid "?")]]
    (is (= nsid (:nsid (scope/parse-include value))))
    (is (scope/supported? value)))
  (doseq [bad [nil "" "include:*" "include:com.example.auth#main" "include:com.example.auth?aud=*"
               "include:com.example.auth?aud=" (str include "?nsid=" nsid)
               (str include-aud "&aud=did:web:other.example.com%23appview")
               (str include "?future=true") "include:com.example.%FF" "repo:com.example.record"]]
    (is (nil? (scope/parse-include bad)) (str bad))))

(deftest constrained-union-audience-inheritance-and-localized-descriptions
  (let [expanded (sets/expand include-aud (schema [repo (assoc repo "action" ["delete"]) rpc inherited]))
        grants (mapv scope/parse (:permissions expanded))]
    (is (= "Feeds" (:title expanded)))
    (is (= {"fr" "Fils" "pt-BR" "Feeds brasileiros"} (:title:lang expanded)))
    (is (= 6 (count grants)))
    (is (= #{audience "*"} (set (map :audience (filter #(= :rpc (:resource %)) grants)))))
    (is (= #{#{"create" "delete" "update"} #{"delete"}} (set (map :actions (filter #(= :repo (:resource %)) grants))))))
  (is (empty? (:permissions (sets/expand include (schema [inherited])))))
  (is (= 1 (count (:permissions (sets/expand include (schema [(assoc rpc "inheritAud" false)]))))) "False inheritance with wildcard audience is explicit")
  (is (= 2 (count (:permissions (sets/expand include (schema [repo repo]))))) "Exact duplicates are collapsed"))

(deftest unknown-or-unauthorized-declarations-never-partially-grant
  (doseq [bad invalid-declarations]
    (is (empty? (:permissions (sets/expand include-aud (schema [bad])))) (pr-str bad)))
  (is (= (:permissions (sets/expand include (schema [repo])))
         (:permissions (sets/expand include-aud (schema (conj invalid-declarations repo)))))))

(deftest invalid-set-envelopes-and-metadata-fail
  (let [good (schema [repo])]
    (doseq [bad [(assoc good "id" "com.example.wrong") (assoc good "lexicon" 2)
                 (assoc-in good ["defs" "main" "type"] "record")
                 (assoc-in good ["defs" "main" "permissions"] nil)
                 (assoc-in good ["defs" "main" "permissions"] (vec (repeat 1001 repo)))
                 (assoc-in good ["defs" "main" "title"] 42)
                 (assoc-in good ["defs" "main" "title:lang"] {"bad_tag" "bad"})
                 (assoc-in good ["defs" "main" "detail:lang"] {"en" nil})]]
      (is (thrown? Exception (sets/expand include bad))))
    (let [large (assoc repo "collection" (mapv #(str "com.example.feed.post" %) (range 100)))
          expanded (sets/expand include (schema [large]))]
      (is (= 100 (count (:permissions expanded))) "A published declaration is not limited to 64 query parameters")
      (is (every? scope/supported? (:permissions expanded))))))

(deftest upstream-include-expansion-agrees-on-valid-and-ignored-declarations
  (when (= "true" (System/getenv "PDS_TEST_UPSTREAM"))
    (let [path (Files/createTempFile "pds-permission-sets-" ".json" (make-array java.nio.file.attribute.FileAttribute 0))
          ;; The upstream method expects the Lexicon envelope validator to have
          ;; removed non-objects before invocation; our interpreter handles both.
          declarations (vec (concat [repo rpc inherited] (filter map? invalid-declarations)))
          fixtures (for [include [include include-aud]
                         permissions [[repo] [rpc inherited] declarations]]
                     (let [document (schema permissions)]
                       {:scope include :set (get-in document ["defs" "main"])
                        :permissions (:permissions (sets/expand include document))}))]
      (try
        (spit (str path) (json/write-str fixtures))
        (let [process (.start (doto (ProcessBuilder. ["node" "scripts/conformance/verify-permission-sets.mjs" (str path)]) (.redirectErrorStream true)))
              finished? (.waitFor process 30 TimeUnit/SECONDS)]
          (when-not finished? (.destroyForcibly process))
          (is finished?)
          (when finished? (is (zero? (.exitValue process)) (slurp (.getInputStream process)))))
        (finally (Files/deleteIfExists path))))))
