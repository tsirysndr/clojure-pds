#!/usr/bin/env bash
set -euo pipefail
# A disposable, password-protected container bound only to loopback.
container_id=$(docker run --detach --rm --publish 127.0.0.1::6379 \
  redis:8.2.3-alpine redis-server --save '' --appendonly no --requirepass pds-test-password)
cleanup() { docker stop "$container_id" >/dev/null 2>&1 || true; }
trap cleanup EXIT
for attempt in $(seq 1 60); do
  if docker exec -e REDISCLI_AUTH=pds-test-password "$container_id" redis-cli ping 2>/dev/null | rg -q PONG; then break; fi
  sleep 0.25
done
mapped_port=$(docker port "$container_id" 6379/tcp | sed 's/.*://')
export PDS_TEST_REDIS_URL="redis://:pds-test-password@127.0.0.1:$mapped_port/0"
# Include optional S3 tests when invoked with --with-s3.
if [[ "${1:-}" == "--with-s3" ]]; then
  uv run scripts/test-s3.py
else
  bash scripts/test-postgres.sh
fi
