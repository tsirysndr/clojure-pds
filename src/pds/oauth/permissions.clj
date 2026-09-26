(ns pds.oauth.permissions
  "Authorization for the currently advertised transitional scopes. Unknown
  protected endpoints require an explicit policy before accepting OAuth."
  (:require [clojure.string :as str]
            [pds.protocol.syntax :as syntax]))

(defn denied! []
  (throw (ex-info "The application has not been granted this permission"
                  {:xrpc true :status 403 :error "insufficient_scope"})))
(defn scopes [account] (set (str/split (:oauth-scope account "") #" ")))
(defn generic! [account]
  (when-not ((scopes account) "transition:generic") (denied!)))
(defn email? [account]
  (or (nil? (:oauth-scope account)) (contains? (scopes account) "transition:email")))
(defn rpc! [account method]
  (generic! account)
  ;; A methodless service token could bypass the separate chat grant. Future
  ;; chat endpoints must have the same policy as those already known today.
  (when-not (syntax/nsid? method) (denied!))
  (when (and (str/starts-with? (str/lower-case method) "chat.bsky.")
             (not ((scopes account) "transition:chat.bsky"))) (denied!))
  (when (= "com.atproto.server.createaccount" (str/lower-case method)) (denied!)))
(defn endpoint! [account request]
  (when-not ((scopes account) "atproto") (denied!))
  (if (::proxy request)
    (rpc! account (subs (:uri request) 6))
    (case (:uri request)
      "/xrpc/com.atproto.server.getSession" nil
      ("/xrpc/com.atproto.repo.createRecord" "/xrpc/com.atproto.repo.putRecord"
       "/xrpc/com.atproto.repo.deleteRecord" "/xrpc/com.atproto.repo.applyWrites"
       "/xrpc/com.atproto.repo.uploadBlob" "/xrpc/com.atproto.server.getServiceAuth") (generic! account)
      (denied!))))
