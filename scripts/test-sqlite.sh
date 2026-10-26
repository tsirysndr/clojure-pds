#!/usr/bin/env bash
set -euo pipefail
PDS_TEST_DATABASE_URL="jdbc:sqlite:" mise exec -- clojure \
  -Sdeps '{:aliases {:sqlite-tests {:extra-paths ["test" "test-integration"]}}}' \
  -M:sqlite-tests -m pds.sqlite-test-runner
