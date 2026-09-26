(ns pds.admin-test
  (:require [clojure.test :refer [deftest is]]
            [pds.admin :as admin]
            [pds.invites :as invites]
            [pds.xrpc :as xrpc])
  (:import [java.util Base64]))

(defn basic [value]
  (str "Basic " (.encodeToString (Base64/getEncoder) (.getBytes ^String value "UTF-8"))))

(deftest configuration-and-admin-authentication
  (is (nil? (:admin-password (admin/settings {}))))
  (is (false? (:invite-required (invites/settings {}))))
  (is (true? (:invite-required (invites/settings {"PDS_REQUIRE_INVITE_CODE" "true"}))))
  (is (thrown? clojure.lang.ExceptionInfo (invites/settings {"PDS_REQUIRE_INVITE_CODE" "yes"})))
  (is (thrown? clojure.lang.ExceptionInfo (admin/settings {"PDS_ADMIN_PASSWORD" "short"})))
  (let [settings (admin/settings {"PDS_ADMIN_PASSWORD" "a-long-admin-password"})
        route (fn [settings]
                (xrpc/router {"/admin" {:method :post :handler (fn [r] (admin/authenticate! settings r)
                                                                       (xrpc/response 200 {}))}}))
        call (fn [settings header] ((route settings) {:uri "/admin" :request-method :post
                                                      :headers {"authorization" header}}))]
    (is (= 200 (:status (call settings (basic "admin:a-long-admin-password")))))
    (is (= 403 (:status (call {} (basic "admin:a-long-admin-password")))))
    (doseq [header [nil "Basic !!!" "Bearer token" (basic "user:a-long-admin-password")
                    (basic "admin:wrong-password") (str "Basic " (apply str (repeat 9000 "A")))]]
      (let [response (call settings header)]
        (is (= 401 (:status response)))
        (is (= "Basic realm=\"PDS admin\", charset=\"UTF-8\"" (get-in response [:headers "WWW-Authenticate"])))))))
