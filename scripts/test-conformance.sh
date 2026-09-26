#!/usr/bin/env bash
set -euo pipefail
# Test-only pinned reference implementation, separate from the Clojure runtime.
npm ci --prefix scripts/conformance --ignore-scripts
PDS_TEST_UPSTREAM=true bash scripts/test-postgres.sh
