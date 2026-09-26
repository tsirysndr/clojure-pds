(ns pds.app-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [pds.app :as app]
            [pds.config :as config]))

(deftest server-discovery
  (let [settings (config/load-config {"PDS_HOSTNAME" "pds.example.com"})
        handler (app/handler settings)
        path "/xrpc/com.atproto.server.describeServer"
        response (handler {:request-method :get :uri path})]
    (is (= 200 (:status response)))
    ;; Required fields from the official describeServer lexicon. Optional
    ;; capabilities are omitted until the corresponding features exist.
    (is (= {"did" "did:web:pds.example.com" "availableUserDomains" [] "blobUploadLimit" 5242880}
           (json/read-str (:body response))))
    (is (= 405 (:status (handler {:request-method :post :uri path}))))
    (is (= 200 (:status (handler {:request-method :head :uri path}))))))
