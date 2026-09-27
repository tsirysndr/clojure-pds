#!/usr/bin/env bash
set -euo pipefail
# Uses an isolated temporary cluster, never a developer's existing database.
# Set PG_BIN to the directory containing initdb/pg_ctl (PostgreSQL 14+).
export PG_BIN="${PG_BIN:-/opt/homebrew/opt/postgresql@18/bin}"
cluster_dir=$(mktemp -d "${TMPDIR:-/tmp}/clojure-pds-pg.XXXXXX")
printf 'clojure-pds integration test\n' > "$cluster_dir/.test-cluster"
port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')
cleanup() {
  local result=$?
  trap - EXIT
  if "$PG_BIN/pg_ctl" -D "$cluster_dir/db" -m fast -w stop >/dev/null 2>&1; then
    if [[ "$result" == 0 ]]; then
      # Delete only this invocation's marked, stopped, owned temporary cluster.
      # Failed runs retain both database and log for diagnosis.
      python3 - "$cluster_dir" "${TMPDIR:-/tmp}" <<'PY' || result=1
import os, pathlib, re, shutil, sys
cluster = pathlib.Path(sys.argv[1])
assert not cluster.is_symlink()
assert cluster.resolve().parent == pathlib.Path(sys.argv[2]).resolve()
assert re.fullmatch(r'clojure-pds-pg\.[A-Za-z0-9]{6}', cluster.name)
assert cluster.stat().st_uid == os.getuid()
assert (cluster / '.test-cluster').read_text() == 'clojure-pds integration test\n'
assert not (cluster / 'db/postmaster.pid').exists()
shutil.rmtree(cluster)
PY
    fi
  elif [[ "$result" == 0 ]]; then
    result=1
  fi
  if [[ "$result" != 0 ]]; then
    printf 'Retained PostgreSQL test cluster for diagnosis: %s\n' "$cluster_dir" >&2
  fi
  exit "$result"
}
trap cleanup EXIT
"$PG_BIN/initdb" -D "$cluster_dir/db" -U pds -A trust --no-locale -E UTF8 >/dev/null
"$PG_BIN/pg_ctl" -D "$cluster_dir/db" -l "$cluster_dir/server.log" \
  -o "-h 127.0.0.1 -p $port -k $cluster_dir" -w start >/dev/null
PDS_DATABASE_USER=pds PDS_DATABASE_PASSWORD='' \
PDS_TEST_DATABASE_URL="jdbc:postgresql://127.0.0.1:$port/postgres" \
  mise exec -- clojure -M:integration
