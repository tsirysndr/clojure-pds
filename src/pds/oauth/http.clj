(ns pds.oauth.http
  "HTTP form and error conventions for non-browser OAuth protocol endpoints."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pds.oauth.dpop :as dpop]
            [pds.oauth.parameters :as parameters]
            [pds.protocol.codec :as codec]
            [pds.request :as request]))

(defn fail! [code message] (throw (ex-info message {:oauth-error code})))
(defn response [status body] {:status status :headers {"Content-Type" "application/json"} :body (json/write-str body)})
(defn form! [request]
  (let [media (some-> (get-in request [:headers "content-type"]) (str/split #";" 2) first str/trim str/lower-case)]
    (when-not (and (#{"application/x-www-form-urlencoded" "application/json"} media)
                   (or (nil? (get-in request [:headers "content-encoding"]))
                       (= "identity" (str/lower-case (get-in request [:headers "content-encoding"])))))
      (fail! "invalid_request" "Expected an unencoded form or JSON body"))
    (try
      (if (= media "application/json")
        ;; Several OAuth clients send PAR as JSON rather than a form. The shape
        ;; is the same flat map of strings, so it is accepted with equal bounds.
        (let [value (json/read-str (codec/text (request/body-bytes request 16384)))]
          (when-not (and (map? value) (<= (count value) 131)
                         (every? (fn [[k v]] (and (string? k) (string? v))) value))
            (fail! "invalid_request" "Expected a JSON object of strings"))
          value)
        (parameters/parse! (codec/text (request/body-bytes request 16384))))
      (catch Exception e
        (if (or (= 413 (:status (ex-data e))) (:oauth-error (ex-data e))) (throw e)
          (fail! "invalid_request" "Invalid form body"))))))

(def descriptions
  {"invalid_request" "Invalid OAuth request" "invalid_request_uri" "Invalid or expired request URI"
   "invalid_client" "Client authentication failed" "invalid_client_metadata" "Invalid client metadata"
   "invalid_dpop_proof" "Invalid DPoP proof" "use_dpop_nonce" "A current DPoP nonce is required"
   "invalid_scope" "Requested scope is not available" "unsupported_response_type" "Unsupported response type"
   "unsupported_grant_type" "Unsupported grant type"
   "invalid_grant" "Invalid authorization grant" "temporarily_unavailable" "Service temporarily unavailable"})

(defn wrap
  "POST protocol endpoint with public-client CORS and nonce headers on every
  response. Browser login/consent routes need a separate cookie/CSRF policy."
  [settings handler]
  (fn [request]
    (let [result (try
                   (case (:request-method request)
                     :options {:status 204 :headers {} :body ""}
                     :post (handler request)
                     (assoc-in (response 405 {:error "invalid_request" :error_description "POST is required"}) [:headers "Allow"] "POST, OPTIONS"))
                   (catch Exception e
                     (let [code (:oauth-error (ex-data e))
                           large? (= 413 (:status (ex-data e)))
                           known? (contains? descriptions code)
                           status (cond large? 413 (= code "temporarily_unavailable") 503 known? 400 :else 500)
                           code (cond large? "invalid_request" known? code :else "server_error")]
                       (response status {:error code :error_description (get descriptions code "An internal server error occurred")}))))]
      (update result :headers merge
              {"Cache-Control" "no-store" "Pragma" "no-cache" "X-Content-Type-Options" "nosniff"
               "Access-Control-Allow-Origin" "*" "Access-Control-Allow-Methods" "POST, OPTIONS"
               "Access-Control-Allow-Headers" "Content-Type, DPoP, Authorization"
               "Access-Control-Expose-Headers" "DPoP-Nonce" "DPoP-Nonce" (dpop/nonce settings)}))))
