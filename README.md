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
bash scripts/test-conformance.sh   # plus pinned upstream repository/proof verifier (Node 22+)
node --test examples/email-worker/handler.test.mjs
mise exec -- clojure -M:repl        # Rebel Readline
```

The GitHub Actions workflow `ci` runs on every push. It uses the mise-pinned JDK,
PostgreSQL and Redis services, a local S3 emulator, the pinned upstream repository
verifier, and the email Worker contract tests. It needs no deployment credentials.

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
| `PDS_RATE_LIMIT_BACKEND` | `memory` | `memory` or `redis`; [Redis settings](docs/REDIS.md) |
| `PDS_RATE_LIMIT_REQUESTS` | `120` | Requests per IP per window |
| `PDS_RATE_LIMIT_WINDOW_SECONDS` | `60` | Fixed window duration |
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

Set `PDS_DID_METHOD=plc` to create portable **did:plc** identities. This requires
an HTTPS public URL and a non-reserved user domain. The default `web` mode creates
**did:web** accounts tied to their hostname and supports local development.
The PDS publishes `/.well-known/atproto-did` for hosted handles and
`/.well-known/did.json` at each did:web account's original hostname. The
identity endpoints resolve local and remote handles, `did:web`, and `did:plc`.
Remote handles use DNS TXT first and HTTPS fallback. `resolveIdentity` and
`refreshIdentity` return a bidirectionally verified handle, or `handle.invalid`.
Set `PDS_PLC_URL` to an HTTPS directory origin (default `https://plc.directory`).
Remote requests use public addresses, verified TLS, bounded bodies and deadlines;
local identities resolve directly from PostgreSQL. Resolution is currently
uncached, with at most 32 concurrent identity requests per server instance.
Portable account migration remains unfinished. The default localhost identities
are development-only.

PLC signup persists separate encrypted repository and rotation keys, the signed
genesis operation, and the handle/email/invite reservation before submitting to
the directory. The optional `recoveryKey` in `createAccount` is a public `did:key`
placed ahead of the server rotation key; retain its private key separately.
No session, repository event, or confirmation email is issued before the signed
directory audit log confirms registration. A `503 RegistrationPending` response
means the reservation remains durable: retry with the same credentials and
recovery key, or sign in after the background worker finishes. Retryable failures
use exponential backoff from 5 seconds to 1 hour; expired worker leases resume the
same operation after restart. Permanent failures remain reserved for an explicit
signup retry; inspect `plc_identities.status` and `last_error` for sanitized status.
Already active accounts use `createSession`, not repeated signup. Account deletion
erases local private keys and content but does not tombstone the public PLC DID.

Authenticated `com.atproto.identity.updateHandle` accepts a hosted handle or a
custom domain whose DNS/HTTPS handle proof resolves to the account's DID. A web
account keeps its original DID hostname reserved and serving its document after
renaming. PLC changes reserve the new handle, sign against the latest verified
directory audit, and commit the local handle and identity event after directory
confirmation. Unrelated DID fields are preserved; changed signing keys or PDS
endpoints require migration instead. Custom-domain proof is rechecked before
submission. A `503 IdentityUpdatePending` leaves a durable job: retry the same
handle or let the background worker finish. Pending jobs block a different handle
change and account deletion. Permanent conflicts retain the reservation; there is
no cancellation/reconciliation admin API yet. Inspect `handle_updates.status`
and `last_error` for sanitized status. `getRecommendedDidCredentials` returns the
account's public repository key, PDS endpoint, handle and available rotation keys.

Set `PDS_REQUIRE_INVITE_CODE=true` to require an invitation during signup;
`PDS_ENABLE_SIGNUP` must also be enabled. Set `PDS_ADMIN_PASSWORD` to a random
secret of at least 16 characters to enable admin endpoints (unset by default).
Use HTTP Basic authentication with username `admin` over your HTTPS origin.
`com.atproto.server.createInviteCode` takes `useCount` and optional `forAccount`;
`createInviteCodes` creates a bounded batch. Ordinary session tokens cannot call
these endpoints. Invite redemption is transactional, including concurrent final uses.
Accounts list their codes with `getAccountInviteCodes`; `includeUsed=false` filters
unavailable codes. Admins can invalidate codes through `disableInviteCodes` by
code or owner. `disableAccountInvites`/`enableAccountInvites` retain the future-grant
policy flag and note; existing codes remain usable. Automatic invite grants are
disabled, so `createAvailable` currently creates no additional codes.

Admins can inspect accounts with `com.atproto.admin.getAccountInfo` and manage
account takedowns through `getSubjectStatus`/`updateSubjectStatus` using a
`com.atproto.admin.defs#repoRef` subject. Takedown blocks content, login, refresh,
and invitations issued by the account. Removing it preserves a preexisting
deactivation; users cannot lift a takedown with `activateAccount`. Changes produce
ordered account-status events. Record/blob moderation is still pending.

Passwords use Argon2id. Access tokens expire after 15 minutes; sessions have a
90-day lifetime. Refresh tokens rotate once; replay revokes the session. Password
reset revokes all sessions and app passwords. Email confirmation/reset tokens expire after 30 minutes
and are single-use. Signup queues confirmation if email delivery is configured.

`deactivateAccount` hides repository content and blocks writes. Primary-password
login, refresh, `getSession`, and `activateAccount` remain available; app sessions
cannot manage activation. Identity metadata remains available while deactivated.
State changes enter the durable event log in commit order. Optional `deleteAfter`
is retained as a recommendation; it does not automatically delete the account.

Email changes require a primary session. `requestEmailUpdate` sends a proof token
to the current address when it is confirmed; `updateEmail` consumes that proof,
invalidates old-address recovery tokens and other sessions, then queues confirmation
for the new address. The current session remains available to finish verification.
Confirmed addresses can enable `emailAuthFactor` through the same proof flow.
Primary login then returns `AuthFactorTokenRequired` and queues a ten-minute,
one-use token; repeat login with `authFactorToken`. App passwords remain usable
without repeating the email factor. Turning the factor off requires fresh email
proof. Challenges and their outbox entries commit before the challenge response.

`requestAccountDelete` sends a one-use deletion token. `deleteAccount` accepts the
DID, primary password and token, removes sessions and hosted content metadata,
erases email/password data, and records a `deleted` tombstone. The DID/handle stay
reserved. PostgreSQL blob bytes are removed transactionally; S3 bytes use a durable
retry queue. Shared/historical repository-block reclamation is tracked separately
on the storage roadmap.

Primary-password sessions can create named app passwords using
`com.atproto.server.createAppPassword` (optional `privileged: true`). The secret is
returned once and only a purpose-keyed digest is stored. Use it with
`createSession`; access scopes are `com.atproto.appPass` or
`com.atproto.appPassPrivileged` and survive refresh. App sessions cannot create
more passwords. `listAppPasswords` returns metadata only; `revokeAppPassword`
deletes dependent access/refresh sessions immediately. These two endpoints also
allow app sessions for their own account, matching upstream behavior. There is a
limit of 100 app passwords per account. Privileged scope is preserved, but chat
and service proxy endpoints are not implemented yet.

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
- Commit events persist signed CAR proofs and previous-value operations for
  inductive verification. Records are limited to 1,000,000 encoded bytes and
  commit proofs to 2,000,000 bytes; oversized batches roll back. The upstream
  conformance suite verifies signatures and reconstructs previous MST roots.
  `com.atproto.sync.subscribeRepos` delivers these events over binary WebSockets.
  Without a cursor it starts live; `cursor=0` replays the backfill window, and a
  nonzero cursor resumes after the last processed sequence (matching the reference
  PDS). The default window is 24 hours, configured with
  `PDS_FIREHOSE_BACKFILL_SECONDS`. Old cursors receive `OutdatedCursor`; future
  cursors fail. Account availability is checked before each content frame.
  `PDS_FIREHOSE_MAX_CLIENTS` defaults to 64 per process and
  `PDS_FIREHOSE_MAX_BACKLOG` to 1,000 new events. Sends have a five-second deadline
  and only one message in flight. Configure your HTTPS proxy to pass WebSocket
  upgrades. The replay window currently limits queries, not physical event retention.
- Blob uploads are buffered and capped at 5 MiB. Bytes use PostgreSQL `bytea` by
  default or a [configurable S3-compatible backend](docs/S3.md). PostgreSQL always
  holds ownership and metadata. Streaming, MIME sniffing, and unreferenced-blob
  cleanup are unfinished.
- Rate limits default to bounded, per-process memory with 120 requests/IP/minute.
  [Optional Redis](docs/REDIS.md) shares counters across instances. Untrusted
  forwarding headers are ignored; reverse proxies need an appropriate limit policy.
- OAuth, relay integration, service proxying, and production
  operations remain on the roadmap. A reference Bluesky client/relay has not yet
  been used for end-to-end conformance testing.

No project license has been selected. Vendored conformance fixtures retain their
upstream CC0 license; vendored Lexicons retain their upstream MIT/Apache notices.
