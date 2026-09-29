(ns pds.oauth.server
  "OAuth route composition, discovery, and transport headers. Header wrapping is
  outside rate limiting so even rejected requests receive nonce/CORS information."
  (:require [clojure.string :as str]
            [pds.oauth.dpop :as dpop]
            [pds.oauth.http :as http]
            [pds.oauth.par :as par]
            [pds.oauth.resource :as resource]
            [pds.oauth.tokens :as tokens]
            [pds.oauth.web :as web])
  (:import [java.net URI]))

(def metadata-paths #{"/.well-known/oauth-authorization-server" "/.well-known/oauth-protected-resource"})
(def protocol-paths #{"/oauth/par" "/oauth/token" "/oauth/revoke"})
(defn validate-origin! [settings]
  (let [value (:public-url settings) uri (try (URI/create value) (catch Exception _ nil))
        scheme (when uri (.getScheme uri)) host (when uri (.getHost uri)) port (when uri (.getPort uri))]
    (when-not (and uri host (= host (str/lower-case host))
                   (or (= "https" scheme) (and (= "http" scheme) (#{"localhost" "127.0.0.1"} host)))
                   (or (= -1 port) (and (<= 1 port 65535) (not= port (if (= "https" scheme) 443 80))))
                   (= value (str scheme "://" host (when (not= -1 port) (str ":" port)))))
      (throw (ex-info "PDS_PUBLIC_URL must be a canonical origin: lowercase hostname, no path or default port (loopback HTTP allowed)" {}))))
  settings)
(defn authorization-metadata [{:keys [public-url]}]
  {:issuer public-url
   :authorization_endpoint (str public-url "/oauth/authorize")
   :pushed_authorization_request_endpoint (str public-url "/oauth/par")
   :token_endpoint (str public-url "/oauth/token")
   :revocation_endpoint (str public-url "/oauth/revoke")
   :response_types_supported ["code"] :response_modes_supported ["query"]
   :grant_types_supported ["authorization_code" "refresh_token"]
   :code_challenge_methods_supported ["S256"]
   :scopes_supported (vec (sort par/supported-scopes))
   :token_endpoint_auth_methods_supported ["none" "private_key_jwt"]
   :token_endpoint_auth_signing_alg_values_supported ["ES256"]
   :revocation_endpoint_auth_methods_supported ["none" "private_key_jwt"]
   :revocation_endpoint_auth_signing_alg_values_supported ["ES256"]
   :dpop_signing_alg_values_supported ["ES256"]
   :authorization_response_iss_parameter_supported true
   :require_pushed_authorization_requests true
   :require_request_uri_registration true
   :client_id_metadata_document_supported true})
(defn resource-metadata [{:keys [public-url]}]
  {:resource public-url :authorization_servers [public-url]
   :scopes_supported (vec (sort par/supported-scopes))
   :bearer_methods_supported ["header"] :dpop_signing_alg_values_supported ["ES256"]})
(defn wrap [handler ds settings resolver]
  (if-not ds handler
    (let [_ (validate-origin! settings)
          browser (web/handler ds settings resolver)
          protocol {"/oauth/par" (par/handler ds settings resolver)
                    "/oauth/token" (tokens/handler ds settings resolver)
                    "/oauth/revoke" (tokens/revocation-handler ds settings resolver)}
          metadata {"/.well-known/oauth-authorization-server" (authorization-metadata settings)
                    "/.well-known/oauth-protected-resource" (resource-metadata settings)}]
      (fn [request]
        (let [path (:uri request)]
          (cond
            (contains? metadata path)
            (case (:request-method request)
              (:get :head) (http/response 200 (get metadata path))
              :options {:status 204 :headers {} :body ""}
              (assoc-in (http/response 405 {:error "invalid_request" :error_description "GET is required"}) [:headers "Allow"] "GET, HEAD, OPTIONS"))
            (contains? protocol path) ((get protocol path) request)
            (or (= path "/oauth/authorize") (str/starts-with? path "/oauth/flow/")) (browser request)
            :else (handler request)))))))

(defn wrap-headers [handler ds settings]
  (if-not ds handler
    (fn [request]
      (let [path (:uri request) protocol? (protocol-paths path) metadata? (metadata-paths path)
            xrpc? (str/starts-with? path "/xrpc/")
            oauth? (or (resource/dpop? request) (contains? (:headers request) "dpop"))
            response (handler request)
            response (if (or protocol? metadata? xrpc?)
                       (update response :headers merge
                               {"Access-Control-Allow-Origin" "*"
                                "Access-Control-Allow-Methods" (cond protocol? "POST, OPTIONS" metadata? "GET, HEAD, OPTIONS" :else "GET, HEAD, POST, OPTIONS")
                                "Access-Control-Allow-Headers" (resource/allow-headers request)
                                "Access-Control-Expose-Headers" "DPoP-Nonce, WWW-Authenticate, Retry-After, Atproto-Repo-Rev, Atproto-Content-Labelers"
                                "Access-Control-Max-Age" "600"
                                "Vary" "Access-Control-Request-Headers"})
                       response)
            response (if (or protocol? metadata? (and xrpc? oauth?))
                       (update response :headers merge {"Cache-Control" "no-store" "Pragma" "no-cache"
                                                       "X-Content-Type-Options" "nosniff"}) response)
            response (if (or protocol? (and xrpc? oauth?))
                       (assoc-in response [:headers "DPoP-Nonce"] (dpop/nonce settings)) response)]
        (if (and xrpc? (= 401 (:status response)))
          (let [discovery (str "resource_metadata=\"" (:public-url settings) "/.well-known/oauth-protected-resource\"")
                challenge (get-in response [:headers "WWW-Authenticate"] "Bearer")]
            (assoc-in response [:headers "WWW-Authenticate"]
                      (if (str/starts-with? challenge "DPoP")
                        (str challenge ", " discovery)
                        (str challenge ", DPoP " discovery))))
          response)))))
