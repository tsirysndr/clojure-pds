(ns pds.api.identity
  (:require [clojure.walk :as walk]
            [pds.accounts :as accounts]
            [pds.db :as db]
            [pds.errors :as errors]
            [pds.identity :as identity]
            [pds.request :as request]
            [pds.xrpc :as xrpc]))

(defn local-handle [ds handle]
  (with-open [conn (db/connection ds)]
    (when-let [row (first (db/query conn "SELECT did, status FROM accounts WHERE handle = ?" handle))]
      (when (= "deleted" (:status row)) (errors/raise! 400 "HandleNotFound" "Handle was not found"))
      (:did row))))

(defn local-document [ds settings did]
  (if (= did (:service-did settings))
    {"id" did "service" [{"id" "#atproto_pds" "type" "AtprotoPersonalDataServer" "serviceEndpoint" (:public-url settings)}]}
    (with-open [conn (db/connection ds)]
      (when-let [account (first (db/query conn "SELECT a.did, a.handle, a.status, r.public_key FROM accounts a LEFT JOIN repositories r ON r.did = a.did WHERE a.did = ?" did))]
        (when (= "deleted" (:status account)) (errors/raise! 400 "DidDeactivated" "DID is deactivated"))
        (walk/stringify-keys (accounts/did-document settings account (:public_key account)))))))

(defn routes [ds settings]
  (let [resolver (identity/resolver (merge settings {:local-handle #(local-handle ds %)
                                                     :local-document #(local-document ds settings %)}))
        route (fn [method f] {:method method :handler #(identity/bounded-call! resolver (fn [] (xrpc/response 200 (f %))))})]
    {"/xrpc/com.atproto.identity.resolveHandle"
     (route :get #(hash-map :did (identity/resolve-handle! resolver (get (request/query-params %) "handle"))))
     "/xrpc/com.atproto.identity.resolveDid"
     (route :get #(hash-map :didDoc (identity/resolve-did! resolver (get (request/query-params %) "did"))))
     "/xrpc/com.atproto.identity.resolveIdentity"
     (route :get #(identity/resolve-identity! resolver (get (request/query-params %) "identifier")))
     ;; Resolution is uncached for now, so a refresh always fetches fresh data.
     "/xrpc/com.atproto.identity.refreshIdentity"
     (route :post #(identity/resolve-identity! resolver (get (request/json-body %) "identifier")))}))
