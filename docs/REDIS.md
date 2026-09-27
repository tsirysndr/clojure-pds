# Optional Redis rate limiting

Redis is optional. The default `PDS_RATE_LIMIT_BACKEND=memory` opens no Redis
connections and holds at most 10,000 active IP buckets in memory. Sessions,
accounts, repository state, and email jobs always remain in PostgreSQL.

| Variable | Default | Purpose |
| --- | --- | --- |
| `PDS_RATE_LIMIT_BACKEND` | `memory` | `memory` or `redis` |
| `PDS_RATE_LIMIT_REQUESTS` | `120` | Maximum admitted requests per window; 1–1,000,000 |
| `PDS_RATE_LIMIT_WINDOW_SECONDS` | `60` | Window duration; 1–3,600 seconds |
| `PDS_RECORD_WRITE_RATE_LIMIT_ENABLED` | `true` | Set `false` to disable record-write rate limiting entirely |
| `PDS_RECORD_WRITE_RATE_LIMIT_REQUESTS` | general request limit | Separate record-write request budget; 1–1,000,000 |
| `PDS_RECORD_WRITE_RATE_LIMIT_WINDOW_SECONDS` | general window | Record-write window; 1–86,400 seconds |
| `PDS_REDIS_URL` | required in Redis mode | `redis://` or `rediss://`, credentials and optional database number |
| `PDS_REDIS_PREFIX` | `clojure-pds` | Namespace shared by all instances of this PDS |

```sh
export PDS_RATE_LIMIT_BACKEND=redis
# Supply PDS_REDIS_URL through a secret manager or process environment:
# rediss://username:password@redis.example.com:6379/0
export PDS_REDIS_PREFIX=my-pds
mise exec -- clojure -M:run
```

URI credentials must be percent-encoded. `rediss://` enables TLS with certificate
and hostname verification using the JVM trust store. The client uses a pool of up
to 16 connections, with two-second connect/read/pool-wait limits. Startup checks
connectivity; the pool closes on shutdown. Jedis is pinned in `deps.edn`.

Redis stores only ephemeral IP-bucket counters (hashed keys), with a TTL. One Lua
script atomically checks/increments a counter and starts its expiry. Separate PDS
instances sharing URL/database/prefix share the same limit; use identical window
and request settings on those instances. Rejected requests do not extend the
window. The limiter uses the Redis clock and does not depend on synchronized PDS
clocks. No `FLUSHDB`, global scans, or persistent application data are used.

The first admitted request starts a fixed window. Exceeding it returns HTTP 429
with `Retry-After`. If explicitly configured Redis becomes unavailable, requests
return sanitized HTTP 503; the server does not silently switch to fresh in-memory
counters. Restarting/flushing/evicting Redis counters resets these transient
limits. Provision capacity for active clients and use a dedicated namespace/DB;
cluster and Sentinel discovery are not implemented.

Both backends use the socket peer address and ignore `X-Forwarded-For`. Behind a
reverse proxy, configure proxy-level limits or a future trusted-proxy policy;
otherwise callers share the proxy's IP budget. The general limiter applies to every route, including health, unless a record-write
override is configured. Proxied requests additionally charge a
[per-account budget](#per-account-proxy-budget); other credential-specific abuse
controls remain work for the full PDS.

## Record-write overrides

Without any `PDS_RECORD_WRITE_RATE_LIMIT_*` settings, record writes share the
existing general IP budget. Setting any of these options selects a separate
budget for POST `com.atproto.repo.createRecord`, `putRecord`, `deleteRecord` and
`applyWrites`. All four share one write budget per socket peer address; a batch
counts as one HTTP request. This is a request limit, not a per-record points quota.
The other endpoints keep the general budget.

For example, allow 3,000 write requests per hour:

```sh
PDS_RECORD_WRITE_RATE_LIMIT_REQUESTS=3000
PDS_RECORD_WRITE_RATE_LIMIT_WINDOW_SECONDS=3600
```

To disable record-write limiting entirely:

```sh
PDS_RECORD_WRITE_RATE_LIMIT_ENABLED=false
```

Disabled write endpoints bypass both counters, including an exhausted or
unavailable general limiter. Authentication, validation and size limits still
apply. The switch works with memory and Redis. Other endpoints still use the
configured backend, so Redis mode continues to require Redis at startup.
Separate Redis write counters use the same pool and a distinct namespace. Use
identical configuration across instances and restart the PDS after changing it.

## Per-account proxy budget

The [authenticated service proxy](PROXY.md) charges each account its own budget
(`PDS_PROXY_ACCOUNT_RATE_LIMIT_*`, 600 requests per 300 seconds by default,
disable with `ENABLED=false`). It uses the configured backend: Redis mode shares
one budget per account across instances in a distinct counter namespace on the
same pool; memory mode counts per process. An unavailable shared limiter fails
closed. The general IP budget still applies to proxied requests.

## Verification

```sh
bash scripts/test-redis.sh
# Also include the local S3 emulator:
bash scripts/test-redis.sh --with-s3
```

The script creates and removes a loopback-only, password-protected Redis 8.2.3
container, then runs the suite with an isolated PostgreSQL cluster. Docker and
PostgreSQL are required; the combined variant also needs `uv`. Tests verify atomic
shared counters under concurrency, expiry, client reopen, IP/namespace isolation,
and sanitized failures. Managed Redis and TLS deployment tests remain unverified.
