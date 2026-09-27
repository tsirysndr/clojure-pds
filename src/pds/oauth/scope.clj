(ns pds.oauth.scope
  "Direct permission scope syntax. Unknown resources or parameters grant nothing."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [pds.oauth.parameters :as parameters]
            [pds.protocol.syntax :as syntax])
  (:import [java.net URI]))

(def transitional #{"atproto" "transition:generic" "transition:chat.bsky" "transition:email"})
(defn- invalid! [] (throw (ex-info "Invalid permission scope" {:oauth-error "invalid_scope"})))
(defn- one-of? [predicate values] (and (seq values) (every? predicate values)))
(defn- service? [value]
  (let [[did fragment :as parts] (str/split value #"#" -1)]
    (and (= 2 (count parts)) (syntax/did? did)
         (or (re-matches #"did:plc:[a-z2-7]{24}" did)
             (and (str/starts-with? did "did:web:")
                  (not (str/includes? (subs did 8) ":"))
                  (let [uri (URI/create (str "https://" (str/replace (subs did 8) "%3A" ":")))]
                    (and (.getHost uri) (nil? (.getUserInfo uri))
                         (or (= -1 (.getPort uri))
                             (and (= "localhost" (.getHost uri)) (<= 1 (.getPort uri) 65535)))))))
         (re-matches #"(?:[A-Za-z0-9._~!$&'()*+,;=:@/?-]|%[0-9A-Fa-f]{2})+" fragment))))
(defn- mime? [value]
  (or (= value "*/*")
      (re-matches #"[A-Za-z0-9!#$&^_.+-]+/(?:[A-Za-z0-9!#$&^_.+-]+|\*)" value)))
(defn parse
  "Return a validated permission or nil. Percent escapes decode once; positional
  '+' is literal, query '+' is form-encoded space. Scalar duplicates are invalid."
  [value]
  (try
    (when-not (and (string? value) (<= 1 (count value) 8192)
                   (re-matches #"[\x21\x23-\x5b\x5d-\x7e]+" value)) (invalid!))
    (if (transitional value) {:resource :transition :name value}
      (let [[head query] (str/split value #"\?" 2)
            [resource positional] (str/split head #":" 2)
            [position arrays allowed] (case resource
                                        "repo" ["collection" #{"collection" "action"} #{"collection" "action"}]
                                        "blob" ["accept" #{"accept"} #{"accept"}]
                                        "rpc" ["lxm" #{"lxm"} #{"lxm" "aud"}]
                                        "account" ["attr" #{} #{"attr" "action"}]
                                        (invalid!))
            params (parameters/parse! (or query "") arrays)
            _ (when-not (set/subset? (set (keys params)) allowed) (invalid!))
            params (if (some? positional)
                     (do (when (contains? params position) (invalid!))
                         ;; Escape delimiters before using the strict UTF-8 decoder.
                         (let [decoded (get (parameters/parse! (str "v=" (-> positional
                                                                                           (str/replace "+" "%2B")
                                                                                           (str/replace "&" "%26")))) "v")]
                           (assoc params position (if (arrays position) [decoded] decoded)))) params)
            nsid-or-star #(or (= "*" %) (syntax/nsid? %))]
        (case resource
          "repo" (let [collections (get params "collection") actions (get params "action" ["create" "update" "delete"])]
                   (when-not (and (one-of? nsid-or-star collections) (one-of? #{"create" "update" "delete"} actions)) (invalid!))
                   {:resource :repo :collections (set collections) :actions (set actions)})
          "blob" (let [accept (get params "accept")]
                   (when-not (one-of? mime? accept) (invalid!))
                   {:resource :blob :accept (set (map str/lower-case accept))})
          "rpc" (let [methods (get params "lxm") audience (get params "aud")]
                  (when-not (and (one-of? nsid-or-star methods) (string? audience)
                                 (or (= "*" audience) (service? audience))
                                 (not (and (= "*" audience) (some #{"*"} methods)))) (invalid!))
                  {:resource :rpc :methods (set methods) :audience audience})
          ;; Other account/identity operations are enabled only when their
          ;; resource handlers implement the corresponding management checks.
          "account" (do (when-not (and (= "email" (get params "attr")) (= "read" (get params "action" "read"))) (invalid!))
                        {:resource :account :attribute "email" :action "read"}))))
    (catch Exception _ nil)))

(defn supported? [value] (some? (parse value)))
(defn permissions [value] (keep parse (str/split (or value "") #" ")))
(defn- names [values] (str/join ", " (sort values)))
(defn describe [value]
  (mapv (fn [token]
          (let [{:keys [resource collections actions accept methods audience]} (parse token)]
            (case resource
              :transition (get {"atproto" "Confirm your account identity."
                                "transition:generic" "Create, change, and delete public records; upload media; access preferences and app services."
                                "transition:email" "Read your email address and verification status."
                                "transition:chat.bsky" "Read and send your private Bluesky chat messages."} token)
              :repo (str (str/capitalize (names actions)) " public records in " (if (collections "*") "all collections" (names collections)) ".")
              :blob (str "Upload media of these types: " (names accept) ".")
              :rpc (str "Call " (if (methods "*") "any API method" (names methods)) " at " (if (= "*" audience) "any service" audience) ".")
              :account "Read your email address and verification status."
              token))) (str/split (or value "") #" ")))
