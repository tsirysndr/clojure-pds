(ns pds.api.admin
  (:require [pds.admin :as admin]
            [pds.admin-accounts :as admin-accounts]
            [pds.api.server :as server]
            [pds.db :as db]
            [pds.invites :as invites]
            [pds.moderation :as moderation]
            [pds.request :as request]))

(defn authenticated [ds settings f]
  (fn [r]
    (admin/authenticate! settings r)
    (db/transact! ds #(f % r))))

(defn routes [ds settings]
  {"/xrpc/com.atproto.admin.updateAccountPassword"
   (server/empty-route (authenticated ds settings #(admin-accounts/update-password! %1 (request/json-body %2))))
   "/xrpc/com.atproto.admin.updateAccountEmail"
   (server/empty-route (authenticated ds settings #(admin-accounts/update-email! %1 (request/json-body %2))))
   "/xrpc/com.atproto.admin.deleteAccount"
   (server/empty-route (authenticated ds settings #(admin-accounts/delete! %1 (request/json-body %2))))
   "/xrpc/com.atproto.admin.getAccountInfo"
   (server/json-route :get (authenticated ds settings #(moderation/account-info %1 (get (request/query-params %2) "did"))))
   "/xrpc/com.atproto.admin.getAccountInfos"
   (server/json-route :get (authenticated ds settings #(moderation/account-infos %1 (request/query-params %2))))
   "/xrpc/com.atproto.admin.getSubjectStatus"
   (server/json-route :get (authenticated ds settings #(moderation/get-status %1 (request/query-params %2))))
   "/xrpc/com.atproto.admin.updateSubjectStatus"
   (server/json-route :post (authenticated ds settings #(moderation/update-status! %1 (request/json-body %2))))
   "/xrpc/com.atproto.server.createInviteCode"
   (server/json-route :post (authenticated ds settings #(invites/create-code! %1 (request/json-body %2))))
   "/xrpc/com.atproto.server.createInviteCodes"
   (server/json-route :post (authenticated ds settings #(invites/create-codes! %1 (request/json-body %2))))
   "/xrpc/com.atproto.admin.disableInviteCodes"
   (server/empty-route (authenticated ds settings #(invites/disable! %1 (request/json-body %2))))
   "/xrpc/com.atproto.admin.disableAccountInvites"
   (server/empty-route (authenticated ds settings #(invites/set-account-enabled! %1 (request/json-body %2) false)))
   "/xrpc/com.atproto.admin.enableAccountInvites"
   (server/empty-route (authenticated ds settings #(invites/set-account-enabled! %1 (request/json-body %2) true)))})
