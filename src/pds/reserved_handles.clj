(ns pds.reserved-handles
  "Operator-reserved first labels beneath the hosted handle domain. Self-service
  signup and handle claims refuse them; an account already holding one keeps it,
  and operator endpoints stay free to register placeholders deliberately."
  (:require [clojure.string :as str]))

(def default
  #{"www" "admin" "administrator" "mail" "email" "smtp" "imap" "pop" "pds" "api" "app"
    "atproto" "cdn" "static" "assets" "media" "abuse" "security" "root" "postmaster"
    "hostmaster" "webmaster" "noreply" "no-reply" "support" "help" "info" "contact"
    "staff" "mod" "moderator" "moderation" "team" "official" "system" "status" "blog"
    "news" "about" "legal" "privacy" "terms" "billing" "payments"})

(defn- label? [value]
  (boolean (re-matches #"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?" value)))

(defn settings [env]
  (let [value (get env "PDS_RESERVED_HANDLES")]
    {:reserved-handles
     (cond
       (nil? value) default
       (str/blank? value) #{}
       :else
       (let [labels (map #(str/lower-case (str/trim %)) (str/split value #"," -1))]
         (when-not (and (string? value) (<= (count value) 8192) (every? label? labels))
           (throw (ex-info "PDS_RESERVED_HANDLES must be comma-separated DNS labels, or empty to disable" {})))
         (set labels)))}))

(defn reserved? [settings handle]
  (and (string? handle)
       (contains? (:reserved-handles settings #{})
                  (str/lower-case (first (str/split handle #"\." 2))))))

(defn blocked?
  "Whether a self-service claim of this hosted handle must be refused. Callers
  establish that the handle belongs to the hosted domain; passing the claimant's
  current handle keeps an account that already holds a reserved name."
  ([settings handle] (blocked? settings handle nil))
  ([settings handle current-handle]
   (and (reserved? settings handle) (not= handle current-handle))))
