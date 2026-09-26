# clojure-pds

An AT Protocol Personal Data Server in Clojure, built in small, independently
reviewable commits. **Work in progress:** this is not yet a federating PDS.

## Development

Install the Clojure CLI and [mise](https://mise.jdx.dev/). The project pins
Temurin JDK `25.0.3+9.0.LTS` in `mise.toml`.

```sh
mise trust
mise install
mise exec -- clojure -M:test
# Start PostgreSQL as described below before running the server.
mise exec -- clojure -M:run
```

Dependencies are pinned in `deps.edn`. Tests use `clojure.test` and are discovered
from `test/**/*_test.clj`; no external test runner is required.
With mise activated in your shell, `clojure -M:test` and `clojure -M:run` also
use the pinned JDK. Explicit `mise exec` works without shell activation.

See [the roadmap](docs/ROADMAP.md) for implementation order and acceptance criteria.
Each feature commit includes its tests and relevant documentation. No license has
been selected yet.

### Interactive REPL

Launch [Rebel Readline](https://github.com/bhauman/rebel-readline) with the pinned JDK:

```sh
mise exec -- clojure -M:repl
```

The `:repl` alias includes source and test namespaces, with completion, syntax
highlighting, and multiline editing. Rebel is a development-only dependency.
Native terminal access is enabled for JDK 25 in this alias. Use `clojure` rather
than `clj` to avoid wrapping Rebel in another readline tool. Exit with Ctrl-D.

For example, query the application handler without starting an HTTP listener:

```clojure
(require '[pds.app :as app] '[pds.config :as config])
(def handler (app/handler (config/load-config {})))
(handler {:request-method :get :uri "/xrpc/_health"})
```

## Configuration

### PostgreSQL

PostgreSQL 14+ is required to run the server. Start a local database with Docker:

```sh
export PDS_DATABASE_PASSWORD='choose-a-local-password'
docker compose up -d postgres
mise exec -- clojure -M:run
```

`PDS_DATABASE_URL` defaults to `jdbc:postgresql://127.0.0.1:5432/clojure_pds`;
`PDS_DATABASE_USER` defaults to `pds`. `PDS_DATABASE_PASSWORD` is passed separately
to the driver. For hosted databases, use the provider's JDBC URL with
`sslmode=verify-full`. Do not commit credentials.

Startup applies checksummed migrations before binding HTTP. To migrate alone:
`mise exec -- clojure -M:migrate`. Existing migrations are immutable; add a new
numbered SQL resource and register it in `pds.db/migrations` for schema changes.

Database tests run against a temporary local PostgreSQL cluster (no Docker needed):

```sh
bash scripts/test-postgres.sh
# Linux or another install:
PG_BIN=/path/to/postgresql/bin bash scripts/test-postgres.sh
```

Alternatively set `PDS_TEST_DATABASE_URL`, `PDS_DATABASE_USER`, and
`PDS_DATABASE_PASSWORD`, then run `mise exec -- clojure -M:integration`. The test
role needs permission to create schemas. Each test uses its own schema and drops
only that schema. Unit/HTTP tests remain available without PostgreSQL via `:test`.

Configuration comes from environment variables (a `.env` file is not loaded).
Invalid values fail before the server starts.

| Variable | Default | Meaning |
| --- | --- | --- |
| `PDS_HOST` | `127.0.0.1` | HTTP bind address |
| `PDS_PORT` | `3000` | TCP port; `0` selects an ephemeral port |
| `PDS_HOSTNAME` | `localhost` | Lowercase DNS hostname for the service DID |

The default `did:web:localhost` is a development placeholder, not a publicly
resolvable identity. Public DID hosting and TLS arrive in later milestones.

## HTTP foundation

`GET /` returns the AT Protocol ASCII banner and a pointer to `/xrpc/` as plain text.

```sh
curl http://127.0.0.1:3000/xrpc/_health
# {"version":"0.1.0-dev"}
curl http://127.0.0.1:3000/xrpc/com.atproto.server.describeServer
# {"did":"did:web:localhost","availableUserDomains":[]}
```

Health reports process liveness only. Unknown paths return a JSON XRPC error and
HTTP 404; unsupported methods return 405 with an `Allow` header. GET routes also
support HEAD. Stop with Ctrl-C to release the listener and worker threads.

`pds.xrpc` dispatches pure request/response maps; `pds.http` adapts them to the
JDK HTTP server. The initial adapter supports UTF-8 response bodies. Binary
streams, WebSockets, CORS, authentication, and federation are still
on the roadmap. Local binding is the default; this milestone is for development.

Discovery follows the required fields of the official
[describeServer lexicon](https://github.com/bluesky-social/atproto/blob/main/lexicons/com/atproto/server/describeServer.json).
The handle-domain list is empty because account creation is not implemented yet.
The service DID is derived from `PDS_HOSTNAME`; publishing its DID document remains
part of the identity milestone.

## Email through a Cloudflare Worker

Set all three variables to enable the PostgreSQL-backed email dispatcher:

```sh
export PDS_EMAIL_WORKER_URL='https://your-email-worker.example.workers.dev/'
export PDS_EMAIL_WORKER_TOKEN='your-shared-secret'
export PDS_EMAIL_FROM='noreply@your-verified-domain.example'
```

Unset all three to disable delivery. Partially configured email fails startup.
The PDS POSTs JSON `{to, from, subject, text, idempotencyKey}` with Bearer
`Authorization` and an `Idempotency-Key` header. Any 2xx means accepted. HTTPS is
required except on loopback for local tests; redirects are never followed.
Timeouts, 408, 429, and 5xx retry with exponential backoff (up to ten attempts).
Other failures stop retrying. Inspect `email_outbox` for failed jobs; error fields
contain sanitized codes. Sent payloads are erased. Failed/pending payloads contain
private account messages and must be protected along with database backups.

A deployable example lives in [examples/email-worker](examples/email-worker).
It requires Cloudflare Email Service, a verified sender domain, and Durable
Objects. Change `PDS_EMAIL_FROM` in `wrangler.toml`, set the shared secret with
`wrangler secret put PDS_EMAIL_TOKEN`, then deploy using Wrangler from that folder.
No Worker is deployed and no real email is sent by the project tests.

The example stores a hashed receipt per message ID to deduplicate successful
retries. Delivery is **at least once**: a crash after the provider sends but before
receipt persistence can cause a duplicate. Receipts are retained indefinitely;
plan retention if operating at scale. The PDS recovers expired delivery leases
following restart. Worker failures never roll back a committed account change.

Run the Worker contract tests with:
`node --test examples/email-worker/handler.test.mjs`.

Cloudflare setup reference:
[Workers email API](https://developers.cloudflare.com/email-service/api/send-emails/workers-api/).

## Accounts and identity

The server now requires `PDS_MASTER_KEY`: a stable base64url-encoded 32-byte secret.
Generate one once, store it in your secret manager, and reuse it on every restart.
It encrypts repository signing keys and derives a separate session-signing key.
Losing or changing it without a migration makes existing private keys unreadable
and invalidates tokens. Back up this key separately from PostgreSQL.

```sh
# Generate once; save securely before restarting the server.
export PDS_MASTER_KEY="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"
export PDS_HOSTNAME='pds.example.com'
export PDS_PUBLIC_URL='https://pds.example.com'
export PDS_USER_DOMAIN='example.com'
export PDS_ENABLE_SIGNUP='true'
```

Registration is disabled by default. The initial identity implementation provisions
**did:web** accounts on direct children of `PDS_USER_DOMAIN`, such as
`alice.example.com`. Configure wildcard DNS and HTTPS routing for those hosts to
this server, preserving the Host header. The service hostname is reserved.
`/.well-known/did.json` and `/.well-known/atproto-did` publish each hosted account's
identity. Local handle resolution is available; remote resolution and did:plc
provisioning/import remain on the roadmap. did:web accounts are tied to their
hostnames and do not provide PLC's portable identity semantics.

Implemented server endpoints include `createAccount`, `createSession`,
`getSession`, `refreshSession`, `deleteSession`, `requestEmailConfirmation`,
`confirmEmail`, `requestPasswordReset`, and `resetPassword`. Signup queues an
email confirmation when delivery is configured. Passwords use Argon2id. Access
JWTs expire after 15 minutes; sessions expire after 90 days. Refresh rotation is
single-use; replay revokes the session, and password reset revokes all sessions.
Email tokens expire after 30 minutes and can be used once.

A bounded in-process IP limiter allows 120 requests/minute. Deployments need
shared limits at their trusted reverse proxy; this server does not trust
client-supplied forwarding headers. This implementation is still in development.

## Repository APIs

Authenticated `com.atproto.repo.createRecord`, `putRecord`, `deleteRecord`, and
`applyWrites` persist records and publish signed version-3 commits in the same
PostgreSQL transaction. The commit and record swap checks prevent lost updates;
batch failures roll back the entire batch. Reads include `getRecord`, paginated
`listRecords`, and `describeRepo`. Sync queries include `getRepo` (CAR export),
`getLatestCommit`, `getRepoStatus`, and paginated `listRepos`.

Records receive generic AT data-model validation and report
`validationStatus: "unknown"`. Explicit `validate: true` currently fails because a
Lexicon catalog validator is not implemented yet. The MST is deterministic and
matches upstream root fixtures, but is rebuilt on each commit (O(n)); incremental
updates are needed before hosting large repositories. Historical blocks are
retained internally; exports include only the current repository graph.

## Blobs

`com.atproto.repo.uploadBlob` accepts authenticated binary uploads up to 5 MiB.
`com.atproto.sync.getBlob` returns the original bytes; `listBlobs` paginates an
account's uploads. CIDs use SHA-256 with the raw codec. Record writes verify that
referenced blobs belong to the author and match their size and MIME metadata.
Uploads are buffered in memory and stored in PostgreSQL `bytea` for now; object
storage, streaming, MIME sniffing, and unreferenced-blob cleanup remain planned.
