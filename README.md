# clojure-pds

An AT Protocol Personal Data Server in Clojure with PostgreSQL persistence.
Built in atomic feature commits. **In development: not yet a fully federating PDS.**

Implemented: hosted did:web accounts, sessions, email confirmation/password reset,
signed repositories, record APIs, CAR export, and binary blobs. See the
[compatibility matrix](docs/COMPATIBILITY.md) for supported behavior and the
[roadmap](docs/ROADMAP.md) for remaining work.

## Run locally

Install the Clojure CLI, [mise](https://mise.jdx.dev/), and PostgreSQL 14+ (or Docker).
The JDK is pinned to Temurin `25.0.3+9.0.LTS` in `mise.toml`.

```sh
mise trust
mise install
export PDS_DATABASE_PASSWORD='choose-a-local-password'
docker compose up -d postgres

# Generate ONCE and save securely; reuse this key across restarts.
export PDS_MASTER_KEY="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"
mise exec -- clojure -M:run
```

The master key encrypts repository signing keys and derives a distinct session
signing key. Back it up separately from PostgreSQL. Losing/changing it without a
key migration makes existing private keys unreadable and invalidates sessions.

`GET /` returns the ASCII PDS banner. Health is liveness, not federation readiness:

```sh
curl http://127.0.0.1:3000/xrpc/_health
curl http://127.0.0.1:3000/xrpc/com.atproto.server.describeServer
```

Stop with Ctrl-C. Configuration comes from exported environment variables;
`.env` files are not automatically loaded. See [.env.example](.env.example).

## Development

```sh
mise exec -- clojure -M:test        # unit, conformance-fixture, HTTP adapter tests
bash scripts/test-postgres.sh      # all tests, isolated PostgreSQL 18 cluster
node --test examples/email-worker/handler.test.mjs
mise exec -- clojure -M:repl        # Rebel Readline
```

`PG_BIN=/path/to/postgresql/bin bash scripts/test-postgres.sh` selects another
PostgreSQL installation. The script stops its temporary cluster after testing;
files remain in the OS temporary directory for diagnosis. It never changes an
existing database. Alternatively set `PDS_TEST_DATABASE_URL` and database
credentials, then run `mise exec -- clojure -M:integration`. Each integration test
creates and drops its own randomly named schema; the role needs schema privileges.

The test runner discovers `*_test.clj` files using `clojure.test`. Dependencies and
upstream conformance fixtures are pinned. `:repl` includes source and test paths;
Rebel stays out of runtime dependencies. Exit with Ctrl-D. Use `clojure`, not `clj`,
to avoid wrapping Rebel in a second readline tool.

```clojure
(require '[pds.app :as app] '[pds.config :as config])
(def handler (app/handler (config/load-config {}))) ; discovery-only, no database
(handler {:request-method :get :uri "/xrpc/_health"})
```

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `PDS_HOST` | `127.0.0.1` | Bind address |
| `PDS_PORT` | `3000` | Port; `0` requests an ephemeral port |
| `PDS_HOSTNAME` | `localhost` | Service DID hostname |
| `PDS_PUBLIC_URL` | `http://localhost:3000` | Public PDS origin; HTTPS outside loopback |
| `PDS_USER_DOMAIN` | `PDS_HOSTNAME`, or `pds.localhost` if unset | Hosted handle suffix |
| `PDS_ENABLE_SIGNUP` | `false` | Enable account creation |
| `PDS_MASTER_KEY` | required | Stable base64url-encoded 32-byte master key |
| `PDS_DATABASE_URL` | `jdbc:postgresql://127.0.0.1:5432/clojure_pds` | PostgreSQL JDBC URL |
| `PDS_DATABASE_USER` | `pds` | Database role |
| `PDS_DATABASE_PASSWORD` | empty | Database password |
| `PDS_BLOB_BACKEND` | `postgres` | `postgres` or `s3`; [S3 settings](docs/S3.md) |
| `PDS_EMAIL_WORKER_URL` | unset | Email Worker HTTPS endpoint |
| `PDS_EMAIL_WORKER_TOKEN` | unset | Worker shared secret |
| `PDS_EMAIL_FROM` | unset | Verified sending address |

For hosted PostgreSQL use `sslmode=verify-full`. Startup runs checksummed,
transactional migrations under an advisory lock before binding HTTP. Run migrations
alone with `mise exec -- clojure -M:migrate`. Applied migrations are immutable;
add numbered SQL files and register them in `pds.db/migrations`.

## Hosted identities and accounts

Set `PDS_HOSTNAME=pds.example.com`, `PDS_PUBLIC_URL=https://pds.example.com`,
`PDS_USER_DOMAIN=example.com`, and `PDS_ENABLE_SIGNUP=true` to provision handles
such as `alice.example.com`. Configure wildcard DNS and HTTPS reverse proxying to
this server, preserving the Host header. The service hostname is reserved.

Accounts currently use **did:web**, tied to their hostname. The PDS publishes
`/.well-known/did.json` and `/.well-known/atproto-did` for hosted accounts. Local
handle resolution works; remote resolution, did:plc creation, and portable account
migration are unfinished. The default localhost identities are development-only.

Passwords use Argon2id. Access tokens expire after 15 minutes; sessions have a
90-day lifetime. Refresh tokens rotate once; replay revokes the session. Password
reset revokes all sessions. Email confirmation/reset tokens expire after 30 minutes
and are single-use. Signup queues confirmation if email delivery is configured.

## Cloudflare Worker email

Configure all three `PDS_EMAIL_*` variables to enable delivery; leave all unset to
disable it. Partial configuration fails startup. Confirmation/reset endpoints fail
explicitly when delivery is disabled.

The PDS POSTs JSON `{to, from, subject, text, idempotencyKey}` with Bearer
`Authorization` and an `Idempotency-Key` header. Any 2xx means accepted. HTTP is
allowed only on loopback; redirects are never followed. Timeouts, 408, 429, and 5xx
retry with exponential backoff, up to ten attempts. Other failures stop retrying.

Messages are queued transactionally with account changes in `email_outbox`.
Workers claim messages with expiring leases; queued delivery survives a restart.
Sent payloads are erased. Failed/pending payloads contain private account messages;
protect the database and backups. Inspect sanitized `last_error` codes for failures.

[examples/email-worker](examples/email-worker) contains the Worker and its contract
tests. Configure the sender in `wrangler.toml`, set the shared secret using
`wrangler secret put PDS_EMAIL_TOKEN`, and deploy using Wrangler. It requires
Cloudflare Email Service, a verified sending domain, and Durable Objects. No Worker
has been deployed and tests send no real email. See Cloudflare's
[Workers email API](https://developers.cloudflare.com/email-service/api/send-emails/workers-api/).

The Worker stores hashed receipts to deduplicate successful retries. Delivery is
**at least once**: a crash after sending but before receipt persistence can produce
a duplicate. Receipts are retained indefinitely; plan retention for larger systems.

## Current limits

- Records always get AT data-model and blob-ownership validation. A pinned local
  catalog validates 17 common record types and their dependencies by default.
  `validate: true` requires a known schema; `validate: false` skips schema checks.
  Results report `validationStatus: "valid"` or `"unknown"`; skip mode omits it.
  See [catalog provenance and scope](resources/lexicons/README.md).
- MSTs match upstream root fixtures but are rebuilt per commit, O(n). Large repos
  need incremental updates. Historical blocks are retained; exports contain the
  current graph. CAR import and garbage collection are unfinished.
- Blob uploads are buffered and capped at 5 MiB. Bytes use PostgreSQL `bytea` by
  default or a [configurable S3-compatible backend](docs/S3.md). PostgreSQL always
  holds ownership and metadata. Streaming, MIME sniffing, and unreferenced-blob
  cleanup are unfinished.
- Rate limits are bounded, per-process, 120 requests/IP/minute. A deployment needs
  shared proxy limits; untrusted forwarding headers are ignored.
- OAuth, WebSocket sync, relay integration, service proxying, and production
  operations remain on the roadmap. A reference Bluesky client/relay has not yet
  been used for end-to-end conformance testing.

No project license has been selected. Vendored conformance fixtures retain their
upstream CC0 license; vendored Lexicons retain their upstream MIT/Apache notices.
