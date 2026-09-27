(ns pds.oauth.permission-sets
  "Pure permission-set validation and namespace-constrained expansion. Unknown
  declarations are ignored whole, never stripped down into broader authority."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [pds.oauth.scope :as scope]
            [pds.protocol.codec :as codec]
            [pds.protocol.formats :as formats]
            [pds.protocol.syntax :as syntax])
  (:import [java.net URLEncoder]))

(defn- invalid! [] (throw (ex-info "Invalid permission set" {:oauth-error "invalid_scope"})))
(defn- text? [value maximum] (and (string? value) (<= (count value) maximum)))
(defn validate!
  "Validate the set envelope. Individual permissions are interpreted separately
  for forward compatibility. Returns the original authenticated schema."
  [nsid schema]
  (let [main (get-in schema ["defs" "main"])]
    (when-not (and (syntax/nsid? nsid) (= nsid (get schema "id")) (= 1 (get schema "lexicon"))
                   (= "com.atproto.lexicon.schema" (get schema "$type"))
                   (= "permission-set" (get main "type"))
                   (vector? (get main "permissions")) (<= (count (get main "permissions")) 1000)
                   (<= (alength (codec/encode schema)) 1000000)) (invalid!))
    (doseq [[field maximum] [["title" 1000] ["detail" 10000]]]
      (when (and (contains? main field) (not (text? (get main field) maximum))) (invalid!))
      (let [lang (str field ":lang") translations (get main lang)]
        (when (contains? main lang)
          (when-not (and (map? translations) (<= (count translations) 100)
                         (every? (fn [[language text]] (and (formats/language? language) (text? text maximum))) translations))
            (invalid!)))))
    schema))

(defn- under? [nsid other]
  (and (syntax/nsid? other) (str/starts-with? other (subs nsid 0 (inc (.lastIndexOf ^String nsid "."))))))
(defn- array-of? [pred values] (and (vector? values) (seq values) (every? pred values)))
(defn- query [pairs]
  (str/join "&" (for [[k v] pairs] (str k "=" (URLEncoder/encode ^String v "UTF-8")))))
(defn- declaration [nsid audience permission]
  (when (and (map? permission) (= "permission" (get permission "type")))
    (let [resource (get permission "resource")
          allowed (case resource "repo" #{"type" "resource" "collection" "action"}
                        "rpc" #{"type" "resource" "lxm" "aud" "inheritAud"} #{})]
      (when (set/subset? (set (keys permission)) allowed)
        (case resource
          "repo" (let [collections (get permission "collection") actions (get permission "action" ["create" "update" "delete"])]
                   (when (and (array-of? #(under? nsid %) collections) (array-of? #{"create" "update" "delete"} actions))
                     (mapv #(str "repo:" % "?" (query (map (fn [a] ["action" a]) (sort (set actions))))) (sort (set collections)))))
          "rpc" (let [methods (get permission "lxm") inherit (get permission "inheritAud" false)
                      inherited? (and (= true inherit) (not (contains? permission "aud")) audience)
                      fixed? (and (= false inherit) (= "*" (get permission "aud")))
                      aud (if inherited? audience "*")]
                  (when (and (or inherited? fixed?) (array-of? #(under? nsid %) methods))
                    (mapv #(str "rpc:" % "?" (query [["aud" aud]])) (sort (set methods)))))
          nil)))))

(defn expand
  "Expand one include against its authenticated schema. Output is ordinary direct
  scope strings, suitable for immutable access-token snapshots. Splitting arrays
  into individual scopes preserves their union without imposing URL size limits
  on a published permission declaration. Display metadata is not authority."
  [include schema]
  (let [{:keys [nsid audience]} (or (scope/parse-include include) (invalid!))
        _ (validate! nsid schema) main (get-in schema ["defs" "main"])
        permissions (vec (distinct (mapcat #(or (declaration nsid audience %) []) (get main "permissions"))))]
    (when (or (> (count permissions) 10000)
              (> (reduce + 0 (map count permissions)) 1000000)) (invalid!))
    {:scope include :nsid nsid :audience audience
     :title (get main "title") :detail (get main "detail")
     :title:lang (get main "title:lang" {}) :detail:lang (get main "detail:lang" {})
     :permissions permissions}))
