(ns pds.api.admin
  (:require [pds.admin :as admin]
            [pds.api.server :as server]
            [pds.db :as db]
            [pds.invites :as invites]
            [pds.request :as request]))

(defn authenticated [ds settings f]
  (fn [r]
    (admin/authenticate! settings r)
    (db/transact! ds #(f % r))))

(defn routes [ds settings]
  {"/xrpc/com.atproto.server.createInviteCode"
   (server/json-route :post (authenticated ds settings #(invites/create-code! %1 (request/json-body %2))))
   "/xrpc/com.atproto.server.createInviteCodes"
   (server/json-route :post (authenticated ds settings #(invites/create-codes! %1 (request/json-body %2))))
   "/xrpc/com.atproto.admin.disableInviteCodes"
   (server/empty-route (authenticated ds settings #(invites/disable! %1 (request/json-body %2))))
   "/xrpc/com.atproto.admin.disableAccountInvites"
   (server/empty-route (authenticated ds settings #(invites/set-account-enabled! %1 (request/json-body %2) false)))
   "/xrpc/com.atproto.admin.enableAccountInvites"
   (server/empty-route (authenticated ds settings #(invites/set-account-enabled! %1 (request/json-body %2) true)))})
