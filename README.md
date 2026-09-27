# clojure-pds

An AT Protocol Personal Data Server in Clojure with PostgreSQL persistence. **In development: not yet a fully federating PDS.**

Implemented: hosted did:web/PLC accounts, sessions, email confirmation/password reset,
signed repositories, record APIs, verified CAR import/export, and binary blobs. See the
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
Use the [offline master-key rotation command](docs/MASTER-KEY.md) to re-encrypt
stored secrets atomically; it invalidates legacy sessions and app passwords.
See the [backup/restore workflow](docs/BACKUP.md) for checksummed PostgreSQL
archives, coordinated S3 backups, recovery steps and the isolated restore drill.

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
verifier, a PostgreSQL backup/restore drill, and the email Worker contract tests.
It needs no deployment credentials.

`PG_BIN=/path/to/postgresql/bin bash scripts/test-postgres.sh` selects another
PostgreSQL installation. The script stops its temporary cluster after testing and
removes it on success; failed runs retain their files in the OS temporary directory
for diagnosis. It never changes an
existing database. Alternatively set `PDS_TEST_DATABASE_URL` and database
credentials, then run `mise exec -- clojure -M:integration`. Each integration test
creates and drops its own randomly named schema; the role needs schema privileges.

Integration tests require Node (CI pins Node 24) for independent passkey signatures.
See [account authentication progress](docs/ACCOUNT-SECURITY.md) for TOTP/passkey
coverage and [OAuth discovery and client verification](docs/OAUTH.md#discovery-and-route-integration). Visit `/account` to create an account
(when signup is enabled), sign in and manage
optional passkeys and Google Authenticator-compatible two-factor authentication.
The Tailwind interface uses the selfhosted/Witchcraft PDS card style with purple
accents and system light/dark themes; its stylesheet is bundled locally. See the
[UI build instructions](docs/ACCOUNT-SECURITY.md#building-the-interface) when editing it.

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
| `PDS_IDENTITY_CACHE_TTL_SECONDS` | `300` | Public remote identity cache TTL; 0–3,600; `0` disables; [freshness policy](docs/IDENTITY-CACHE.md) |
| `PDS_IDENTITY_CACHE_MAX_ENTRIES` | `1024` | Maximum cached/in-flight identity entries; 1–10,000 |
| `PDS_ENABLE_SIGNUP` | `false` | Enable account creation |
| `PDS_MASTER_KEY` | required | Stable base64url-encoded 32-byte master key |
| `PDS_DATABASE_URL` | `jdbc:postgresql://127.0.0.1:5432/clojure_pds` | PostgreSQL JDBC URL |
| `PDS_DATABASE_USER` | `pds` | Database role |
| `PDS_DATABASE_PASSWORD` | empty | Database password |
| `PDS_DB_POOL_SIZE` | `20` | Maximum PostgreSQL connections per server; 1–256; [pool lifecycle](docs/DATABASE.md) |
| `PDS_DB_POOL_TIMEOUT_MS` | `5000` | Maximum wait to borrow a connection; 500–60,000 ms |
| `PDS_BLOB_BACKEND` | `postgres` | `postgres` or `s3`; [S3 settings](docs/S3.md) |
| `PDS_RATE_LIMIT_BACKEND` | `memory` | `memory` or `redis`; [Redis settings](docs/REDIS.md) |
| `PDS_RECORD_WRITE_RATE_LIMIT_ENABLED` | `true` | `false` disables record-write limits; [write budget settings](docs/REDIS.md#record-write-overrides) |
| `PDS_RATE_LIMIT_REQUESTS` | `120` | Requests per IP per window |
| `PDS_RATE_LIMIT_WINDOW_SECONDS` | `60` | Fixed window duration |
| `PDS_RELAY_URLS` | unset | Comma-separated relay HTTPS origins; [durable announcements](docs/RELAY.md) |
| `PDS_RELAY_INTERVAL_SECONDS` | `1200` | Interval between successful relay announcements |
| `PDS_EMAIL_WORKER_URL` | unset | Email Worker HTTPS endpoint |
| `PDS_EMAIL_WORKER_TOKEN` | unset | Worker shared secret |
| `PDS_EMAIL_FROM` | unset | Verified sending address |

For hosted PostgreSQL use `sslmode=verify-full`. Startup runs checksummed,
transactional migrations under an advisory lock before binding HTTP. Run migrations
alone with `mise exec -- clojure -M:migrate`. Applied migrations are immutable;
add numbered SQL files and register them in `pds.db/migrations`.

## Hosted identities and accounts

Bluesky [private preferences and migration](docs/PREFERENCES.md) are stored locally
through `app.bsky.actor.getPreferences` and `putPreferences`.

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
local web identities resolve directly from PostgreSQL. PLC resolution fetches and
verifies the directory's full signed audit history, then derives the current DID
document, including for hosted accounts. External migration, recovery and rotation
therefore supersede the local snapshot. Invalid audits fail without a stale local
fallback; timestamps and history freshness still rely on the directory. Audits
are bounded to 4 MiB and 10,000 operations, with redirects disabled. Public remote
resolution uses a [bounded five-minute cache](docs/IDENTITY-CACHE.md), configurable
or disabled through environment settings. `refreshIdentity` forces fresh lookup
and invalidates related cached bindings. Hosted database reads and internal
security-sensitive resolution remain uncached. At most 32 concurrent requests
enter the public identity resolver per server instance.
External end-to-end migration conformance remains unverified. The default localhost identities
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
Operators can [rotate managed control and repository signing keys](docs/KEY-ROTATION.md)
with `clojure -M:identity`. Control-key rotation preserves recovery priority.
Signing-key rotation creates a new signed commit over the same records and emits
identity/sync checkpoints. PLC changes resume through the durable identity worker;
hosted web signing-key changes commit atomically.

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

For outgoing PLC migration, call `com.atproto.identity.requestPlcOperationSignature`
with a primary session to enqueue a 30-minute email code through the configured
Worker. Pass that code as `token` to `com.atproto.identity.signPlcOperation`, with
any replacement `rotationKeys`, `alsoKnownAs`, `verificationMethods`, or `services`.
Omitted fields retain the latest verified directory values. The code is bound to
the account and email address and consumed once, atomically with signing; failed
validation or unavailable directory access leaves it usable until expiry. The
response contains a signed successor operation. It does not submit the operation
or change local credentials. App-password sessions cannot request or use these
codes. Existing primary sessions can use this flow while deactivated or taken
down; restricted login/recovery for taken-down accounts remains pending. Pending
identity updates must finish first.

`com.atproto.identity.submitPlcOperation` accepts a signed operation that retains
this account's current handle, local repository key, server rotation key, and
configured PDS service endpoint. It verifies authorization against the current
directory audit before queueing, persists the operation in `handle_updates` with
`operation_kind='submit'`, and confirms directory acceptance before updating local
metadata and publishing an identity event. Identical retries reconcile without
duplicate events. A `503 IdentityUpdatePending` uses the same durable retry and
lease behavior as handle changes. Deactivated primary sessions can submit without
activating the account. This endpoint supports normal successors and already
accepted operations; recovery forks remain pending.

Destination account preparation supports `createAccount` with an existing
`did:plc` or resolvable `did:web`. Authenticate with a service token from the source
PDS, addressed to this PDS and bound to `com.atproto.server.createAccount`. The
token issuer must exactly match `did`. Signup and invitation settings apply;
custom handles must prove their binding through DNS/HTTPS. The transaction consumes
the token and invite, reserves the account and handle, generates fresh encrypted
local keys, creates a private empty repository, and queues confirmation email.
The returned primary session works while the account is deactivated. PLC accounts
can obtain `getRecommendedDidCredentials`, have the source sign those credentials,
and call `submitPlcOperation` here. Account preparation publishes no repository
events and does not alter the remote DID. A source identity snapshot is retained
for repository validation. After importing the repository and transferring DID
credentials, call `activateAccount` with the destination primary session. It
freshly verifies the remote signing key, PDS endpoint and handle; PLC identities
must also retain the destination's rotation key. Custom handles must still prove
their DNS/HTTPS binding. Activation rechecks authorization and local state after
network I/O, then publishes identity, active-account and sync events atomically.
Missing repository import returns `MigrationIncomplete`; mismatched credentials
return `IdentityMismatch`. Admin activation cannot bypass these checks. Blob
transfer can continue after activation; use `listMissingBlobs` to verify it.
This flow is tested against local HTTP/TLS fixtures, not an external reference PDS.

`POST /xrpc/com.atproto.repo.importRepo` accepts a complete version-3 CAR with
`Content-Type: application/vnd.ipld.car` and a primary access session. For prepared
destinations, it verifies the source signature against the retained DID document;
for local backup restoration, it uses the current local signing key. It validates
the complete MST and records before atomically replacing the record index,
retaining only reachable blocks, and signing a fresh destination commit. The new
revision exceeds both the imported and local revisions. Active accounts publish
a sync checkpoint; deactivated accounts remain private. Authorization is checked
again after parsing, and concurrent repository changes return `409 InvalidSwap`.
Imports are buffered, limited to 64 MiB and two simultaneous imports per process;
capacity exhaustion returns `503 RepoImportBusy`. Blob bytes are transferred
separately using `uploadBlob`, including through inactive primary sessions.
Imported historical record objects are preserved without current Lexicon checks.

`GET /xrpc/com.atproto.repo.listMissingBlobs` lists referenced blob CIDs absent
from the authenticated account's blob metadata, with a representative `recordUri`.
It accepts `limit` (1–1000, default 500) and a CID cursor. Nested modern blob
objects and legacy `cid`/`mimeType` objects are indexed transactionally during
record writes and imports; ordinary CID links do not imply blob ownership.
Uploads to either PostgreSQL or S3 remove the matching CID from this list.
Inactive accounts' repositories and blobs remain hidden from public reads.
This endpoint checks database metadata; it does not probe S3 for lost objects.

Public `com.atproto.sync.listBlobs` lists the CIDs referenced by current records,
including references whose bytes have not arrived yet. Unreferenced uploads are
excluded. The optional `since` TID selects records changed after that repository
revision; the CID cursor and `limit` paginate the distinct result. Unchanged puts
keep their previous record revision. Imported records use the new destination
revision. Upgrading an older database assigns existing records the current repo
revision as a conservative baseline; it does not reconstruct historical revisions.

`GET /xrpc/com.atproto.server.checkAccountStatus` reports the authenticated
account's activation state, repository head/revision, owned block count, indexed
record count, distinct referenced blob count and stored blob count. Primary
sessions work while inactive; active app sessions may also read their own status.
`validDid` uses fresh remote resolution to check the signing key, PDS endpoint and
PLC rotation authority. Resolution failure returns `false` without hiding transfer
counts. It does not attest to handle binding or completed blob transfer, so use
`listMissingBlobs` as well. Counts include retained historical blocks and uploaded
unreferenced blobs; `privateStateValues` is currently zero. Responses are not cached.

`GET /xrpc/com.atproto.server.getServiceAuth` issues a short-lived service JWT for
the authenticated account. Supply `aud` as a service DID or `did#serviceId` reference
(encode `#` as `%23` in the query), and preferably `lxm` as the exact target method.
Tokens use the repository signing key and a fresh random nonce. They expire after
60 seconds by default; an explicit `exp` may extend a method-bound token to at most
one hour. A method-less legacy token is limited to one minute. Protected account
methods cannot be authorized this way. Chat methods and `createAccount` require
a primary or privileged app-password session. Existing primary sessions for
taken-down accounts can request only `createAccount` for migration. Responses are
marked `Cache-Control: no-store`. These tokens cannot be used as local access or
refresh sessions. Issued tokens cannot be individually revoked before expiration.

Receiving-side verification and replay protection authenticate destination account
preparation. They require an exact method and PDS audience (the service DID or its
`#atproto_pds` reference), `typ=JWT`, and the issuer's current `#atproto` key resolved
through the bounded identity resolver. The maximum accepted lifetime is one hour,
with 30 seconds of allowance for a future issue timestamp. Expiration is rechecked
after resolution and before consuming the proof. PostgreSQL stores a hash of each
used nonce with its issuer, atomically with the protected mutation; rolled-back
mutations retain retryability. Cleanup removes at most 1,000 expired entries per
successful consumption. Authenticated service proxying is described below. Other existing HTTP
endpoints retain their current session authentication.

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

[Administrative account recovery](docs/ADMIN-ACCOUNTS.md) supports password and
email updates and account deletion. Credential recovery revokes existing sessions
and email proofs while preserving enrolled TOTP and passkeys. A separate local
`clojure -M:account-admin recover-authenticators` command handles loss of every
factor with a new password, security-version checks and an audit receipt.

Admins can inspect accounts with `com.atproto.admin.getAccountInfo` and manage
account takedowns through `getSubjectStatus`/`updateSubjectStatus` using a
`com.atproto.admin.defs#repoRef` subject. Takedown blocks content, login, refresh,
and invitations issued by the account. Removing it preserves a preexisting
deactivation; users cannot lift a takedown with `activateAccount`. Changes produce
ordered account-status events. [Record and blob takedowns](docs/MODERATION.md)
use strongRef/repoBlobRef subjects. Record flags hide indexed reads while
preserving signed sync data; blob flags block downloads, reuploads and new
references with both PostgreSQL and S3 storage.

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
limit of 100 app passwords per account. Privileged scope authorizes proxied chat
methods through the shared service-authentication policy.

## Service proxy

Authenticated clients can send `atproto-proxy: <DID>#<service>` on unimplemented
XRPC routes to select an external service. Configure `PDS_APPVIEW_SERVICE` and
`PDS_LABELER_SERVICE` for default destinations. Each request receives a fresh,
method-bound repository-key JWT; local session credentials stay on this PDS.
GET, HEAD and bounded POST transfers are supported with guarded HTTPS, fresh
DID resolution, concurrency limits and existing memory/Redis rate limits. See
[proxy configuration and behavior](docs/PROXY.md) for limits and verification.

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

- Record write APIs enforce AT data-model and blob-ownership validation. A pinned local
  catalog validates 17 common record types and their dependencies by default.
  `validate: true` requires a known schema; `validate: false` skips schema checks.
  Results report `validationStatus: "valid"` or `"unknown"`; skip mode omits it.
  See [catalog provenance and scope](resources/lexicons/README.md).
- All 58 implemented protocol endpoints have pinned Lexicon definitions. JSON
  request bodies and query parameters are validated when handlers read them,
  preserving authentication order. Defaults, required/nullable fields, formats,
  array parameters and nested unions are checked; extension fields remain allowed.
  Binary uploads retain their bounded readers. Integration tests check actual
  responses for every implemented endpoint and all repository event variants
  against local and pinned upstream validators. Dynamic Lexicon resolution and
  external client/relay conformance remain unfinished.
- MSTs match upstream root fixtures but are rebuilt per commit, O(n). Large repos
  need incremental updates. Historical blocks are retained; exports contain the
  current graph. CAR imports are buffered and replace the full current record set;
  streaming import and historical repository-block garbage collection are unfinished.
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
- Blob uploads accept zero bytes through 5 MiB and are buffered. Bytes use PostgreSQL `bytea` by
  default or a [configurable S3-compatible backend](docs/S3.md). PostgreSQL always
  holds ownership and metadata. Temporary uploads are private until referenced;
  re-uploading them renews their grace period. `PDS_BLOB_TEMP_TTL_SECONDS` defaults
  to 86400 (24 hours), with an allowed range of 3600–2592000 seconds. A worker checks
  once per minute, collecting at most 50 expired unreferenced blobs from one account.
  Losing the final current record reference removes blob metadata in that record
  transaction. PostgreSQL bytes are removed immediately; S3 locators enter the
  durable deletion queue. Fresh S3 uploads use unique object keys so a delayed
  deletion cannot erase a later upload with the same CID. Streaming, MIME sniffing,
  and discovering S3 orphans left by failed metadata commits remain unfinished.
- Rate limits default to bounded, per-process memory with 120 requests/IP/minute.
  [Optional Redis](docs/REDIS.md) shares counters across instances. Untrusted
  forwarding headers are ignored; reverse proxies need an appropriate limit policy.
- OAuth, external service/relay interoperability, and production
  operations remain on the roadmap. A reference Bluesky client/relay has not yet
  been used for end-to-end conformance testing.

No project license has been selected. Vendored conformance fixtures retain their
upstream CC0 license; vendored Lexicons retain their upstream MIT/Apache notices.
