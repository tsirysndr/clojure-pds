#!/usr/bin/env bash
set -euo pipefail
# Uses an isolated temporary cluster, never a developer's existing database.
# Set PG_BIN to the directory containing initdb/pg_ctl (PostgreSQL 14+).
PG_BIN="${PG_BIN:-/opt/homebrew/opt/postgresql@18/bin}"
cluster_dir=$(mktemp -d "${TMPDIR:-/tmp}/clojure-pds-pg.XXXXXX")
port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')
cleanup() {
  "$PG_BIN/pg_ctl" -D "$cluster_dir/db" -m fast -w stop >/dev/null 2>&1 || true
  # Retain files on failure for diagnosis; successful clusters are also harmless
  # temporary files managed by the OS. No recursive delete of an arbitrary path.
}
trap cleanup EXIT
"$PG_BIN/initdb" -D "$cluster_dir/db" -U pds -A trust --no-locale -E UTF8 >/dev/null
"$PG_BIN/pg_ctl" -D "$cluster_dir/db" -l "$cluster_dir/server.log" \
  -o "-h 127.0.0.1 -p $port -k $cluster_dir" -w start >/dev/null
PDS_DATABASE_USER=pds PDS_DATABASE_PASSWORD='' \
PDS_TEST_DATABASE_URL="jdbc:postgresql://127.0.0.1:$port/postgres" \
  mise exec -- clojure -M:integration
