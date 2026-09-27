(ns pds.oauth.permissions
  "Direct and transitional OAuth permissions. Resource operations enforce their
  parameters inside the authenticated mutation, after endpoint admission."
  (:require [clojure.string :as str]
            [pds.oauth.scope :as scope]
            [pds.protocol.syntax :as syntax]))

(defn denied! []
  (throw (ex-info "The application has not been granted this permission"
                  {:xrpc true :status 403 :error "insufficient_scope"})))
(defn scopes [account] (set (str/split (:oauth-scope account "") #" ")))
(defn- grants [account resource] (filter #(= resource (:resource %)) (scope/permissions (:oauth-scope account))))
(defn- generic? [account] (contains? (scopes account) "transition:generic"))
(defn- resource! [account resource]
  (when-not (or (generic? account) (seq (grants account resource))) (denied!)))
(defn email? [account]
  (or (nil? (:oauth-scope account)) (contains? (scopes account) "transition:email")
      (some #(= "email" (:attribute %)) (grants account :account))))
(defn repo! [account collection action]
  (when (and (:oauth-scope account) (not (generic? account))
             (not-any? #(and ((:actions %) (name action))
                            (or ((:collections %) "*") ((:collections %) collection))) (grants account :repo)))
    (denied!)))
(defn blob! [account mime]
  (when (and (:oauth-scope account) (not (generic? account))
             (not-any? (fn [{:keys [accept]}]
                         (some #(or (= % "*/*") (= % mime)
                                    (and (str/ends-with? % "/*") (str/starts-with? mime (subs % 0 (dec (count %)))))) accept))
                       (grants account :blob)))
    (denied!)))
(defn rpc! [account method audience]
  (when-not (and (syntax/nsid? method) (string? audience)) (denied!))
  (when-not
    (or (some #(and (or (= "*" (:audience %)) (= audience (:audience %)))
                    (or ((:methods %) "*") ((:methods %) method))) (grants account :rpc))
        (and (generic? account)
             (not= "com.atproto.server.createaccount" (str/lower-case method))
             (or (not (str/starts-with? (str/lower-case method) "chat.bsky."))
                 ((scopes account) "transition:chat.bsky"))))
    (denied!)))
(defn endpoint! [account request]
  (when-not ((scopes account) "atproto") (denied!))
  (if (::proxy request)
    (resource! account :rpc)
    (case (:uri request)
      "/xrpc/com.atproto.server.getSession" nil
      ("/xrpc/com.atproto.repo.createRecord" "/xrpc/com.atproto.repo.putRecord"
       "/xrpc/com.atproto.repo.deleteRecord" "/xrpc/com.atproto.repo.applyWrites") (resource! account :repo)
      "/xrpc/com.atproto.repo.uploadBlob" (resource! account :blob)
      "/xrpc/com.atproto.server.getServiceAuth" (resource! account :rpc)
      (denied!))))
