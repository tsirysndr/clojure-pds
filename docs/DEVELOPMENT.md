# Development

```sh
mise exec -- clojure -M:test        # unit, conformance-fixture, HTTP adapter tests
bash scripts/test-postgres.sh      # all tests, isolated PostgreSQL 18 cluster
bash scripts/test-conformance.sh   # plus pinned upstream repository/proof verifier (Node 22+)
node --test examples/email-worker/handler.test.mjs
npm run --prefix frontend test     # React account/OAuth pages (vitest + Testing Library)
mise exec -- clojure -M:repl        # Rebel Readline
```

The browser account/OAuth interface is a React app in `frontend/`; see
[building the interface](ACCOUNT-SECURITY.md#building-the-interface) for its
build, test and dev-server workflow.

The GitHub Actions workflow `ci` runs on every push. It uses the mise-pinned JDK,
PostgreSQL and Redis services, a local S3 emulator, the pinned upstream repository
verifier, a PostgreSQL backup/restore drill, and the email Worker contract tests.
It needs no deployment credentials.

`PG_BIN=/path/to/postgresql/bin bash scripts/test-postgres.sh` selects another
PostgreSQL installation. The script stops its temporary cluster after testing and
removes it on success; failed runs retain their files in the OS temporary directory
for diagnosis. It never changes an existing database. Alternatively set
`PDS_TEST_DATABASE_URL` and database credentials, then run
`mise exec -- clojure -M:integration`. Each integration test creates and drops its
own randomly named schema; the role needs schema privileges.

Integration tests require Node (CI pins Node 24) for independent passkey signatures.
See [account authentication progress](ACCOUNT-SECURITY.md) for TOTP/passkey
coverage and [OAuth discovery and client verification](OAUTH.md#discovery-and-route-integration).

The test runner discovers `*_test.clj` files using `clojure.test`. Dependencies and
upstream conformance fixtures are pinned. `:repl` includes source and test paths;
Rebel stays out of runtime dependencies. Exit with Ctrl-D. Use `clojure`, not `clj`,
to avoid wrapping Rebel in a second readline tool.

```clojure
(require '[pds.app :as app] '[pds.config :as config])
(def handler (app/handler (config/load-config {}))) ; discovery-only, no database
(handler {:request-method :get :uri "/xrpc/_health"})
```
