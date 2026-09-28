#!/usr/bin/env bash
set -euo pipefail
# Runs the SQLite-verified portion of the integration matrix against temporary
# database files (no database service required). Namespaces are added here as
# dialect coverage grows; docs/SQLITE.md records what remains PostgreSQL-only.
namespaces=(
  pds.sqlite-backend-test
  pds.repo-test
  pds.events-test
  pds.invites-test
  pds.app-passwords-test
  pds.auth-test
  pds.moderation-test
  pds.blob-api-test
  pds.firehose-test
  pds.account-lifecycle-test
  pds.preferences-test
  pds.email-outbox-test
)
requires=$(printf "'%s " "${namespaces[@]}")
symbols=$(printf "'%s " "${namespaces[@]}")
PDS_TEST_DATABASE_URL="jdbc:sqlite:" mise exec -- clojure \
  -Sdeps '{:aliases {:sqlite-tests {:extra-paths ["test" "test-integration"]}}}' -M:sqlite-tests \
  -e "(require 'clojure.test ${requires})
      (let [r (apply clojure.test/run-tests [${symbols}])]
        (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))"
