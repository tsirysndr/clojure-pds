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
