(ns pds.sqlite-test-runner
  "SQLite integration coverage shared by local development and Dagger CI."
  (:require [clojure.test :as test]))

(def namespaces
  '[pds.sqlite-backend-test
    pds.account-lifecycle-test
    pds.account-recovery-test
    pds.account-status-test
    pds.admin-accounts-test
    pds.app-passwords-test
    pds.auth-test
    pds.moderation-test
    pds.content-moderation-test
    pds.blob-api-test
    pds.blob-download-test
    pds.blob-migration-test
    pds.blob-storage-test
    pds.blob-sync-test
    pds.blob-upload-test
    pds.empty-blob-test
    pds.browser-identity-test
    pds.browser-security-test
    pds.passkeys-test
    pds.totp-test
    pds.dynamic-record-test
    pds.email-outbox-test
    pds.email-security-test
    pds.endpoint-api-test
    pds.response-contract-test
    pds.events-test
    pds.firehose-test
    pds.invites-test
    pds.preferences-test
    pds.reserved-keys-test
    pds.handles-test
    pds.identity-api-test
    pds.migration-test
    pds.migration-activation-test
    pds.oauth-cleanup-test
    pds.oauth-client-auth-test
    pds.oauth-dpop-test
    pds.oauth-include-test
    pds.oauth-interaction-test
    pds.oauth-management-test
    pds.oauth-migration-test
    pds.oauth-par-test
    pds.oauth-permission-cache-test
    pds.oauth-permissions-test
    pds.oauth-resource-test
    pds.oauth-server-test
    pds.oauth-sessions-test
    pds.oauth-tokens-test
    pds.oauth-web-test
    pds.plc-recovery-keys-test
    pds.plc-signing-test
    pds.plc-submission-test
    pds.signing-keys-test
    pds.proxy-api-test
    pds.proxy-stream-test
    pds.relay-integration-test
    pds.reserved-handles-integration-test
    pds.repo-api-test
    pds.repo-export-test
    pds.repo-import-test
    pds.repo-import-stream-test
    pds.repo-test
    pds.server-api-test
    pds.service-auth-api-test
    pds.service-auth-receive-test
    pds.sync-api-test
    pds.sync-stream-test])

(defn -main [& _]
  (doseq [namespace namespaces] (require namespace))
  (let [{:keys [fail error]} (apply test/run-tests namespaces)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
