# PostgreSQL connections

The server uses a single HikariCP 7.1.0 pool shared by HTTP handlers, firehose
readers and background jobs. `PDS_DB_POOL_SIZE` sets its maximum connections
(default 20, range 1–256). `PDS_DB_POOL_TIMEOUT_MS` bounds waiting to borrow a
connection (default 5,000 ms, range 500–60,000). Both settings are validated at
startup; restart the server after changing them. The pool grows on demand and
can shrink to zero idle connections. It uses Hikari's default idle retirement,
connection lifetime and keepalive intervals.

Size the total across all PDS processes within PostgreSQL's connection budget,
leaving room for administration, migrations and other applications. HTTP requests
and background work compete for this same budget. The limit bounds database
connections, not the number of waiting HTTP requests. XRPC requests that time out
borrowing a connection receive a sanitized 503 `ServiceUnavailable` with
`Retry-After: 1`; they do not expose driver messages or connection settings.

The PostgreSQL driver retains a five-second connection timeout and a 30-second
socket read timeout, with TCP keepalive enabled. Pool acquisition timeout is not
a SQL execution timeout. Network failure detection also depends on operating
system TCP keepalive settings. SQL and lock timeouts still need appropriate
deployment policies; individual bounded jobs use transaction-local timeouts.
Database credentials and TLS parameters continue to come from
`PDS_DATABASE_URL`, `PDS_DATABASE_USER` and `PDS_DATABASE_PASSWORD`.

Startup verifies database connectivity before applying transactional migrations.
Startup failures close the pool, including failures during configuration,
migrations or later dependency initialization. Shutdown stops the relay worker,
HTTP server and remaining workers before closing the database pool. Closure is
idempotent and also runs inside the JVM shutdown hook.

Each transaction owns one borrowed connection. Commit or rollback precedes
returning it; closing an uncommitted connection rolls it back. JDBC auto-commit,
read-only and transaction isolation changes are reset on reuse. Connections
default to read-committed isolation. Use `SET LOCAL` inside a transaction for
SQL session settings: a pool does not reset arbitrary SQL `SET` commands,
temporary tables or session-level advisory locks. Production code uses
transaction-scoped advisory locks. Do not keep connections while doing remote
network calls or nest connection acquisition in an existing transaction.

The migration CLI and disposable integration fixture factory retain unpooled
connections; tools that use `db/open-pool!` must close the returned datasource.
Real PostgreSQL pool tests cover exhaustion, reuse, rollback, JDBC state reset,
concurrent transactions, broken-connection replacement, startup failure cleanup,
and HTTP account/record operations with a one-connection pool. The process smoke
test exercises actual server startup and shutdown. Full deployment load testing,
database failover and backup/restore verification remain pending.

Configuration semantics: [HikariCP 7.1.0 documentation](https://github.com/brettwooldridge/HikariCP/tree/HikariCP-7.1.0#gear-configuration-knobs-baby).
