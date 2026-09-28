# SQLite backend

When none of `PDS_DATABASE_URL`, `PDS_DATABASE_USER`, or
`PDS_DATABASE_PASSWORD` is configured, the server stores everything in one
SQLite file (`PDS_SQLITE_PATH`, default `data/clojure-pds.sqlite3`; WAL and
shared-memory sidecar files appear next to it). An explicit
`PDS_DATABASE_URL=jdbc:sqlite:<path>` selects it directly. This backend is
newer than the PostgreSQL one: it passes 66 of the integration namespaces
(see below), with a documented set of PostgreSQL-mechanics tests that do not
apply. Prefer PostgreSQL for anything beyond a single small host.

## How it works

The persistence code remains written against PostgreSQL. `pds.db.sqlite`
adapts it at the statement boundary:

- A cached translator rewrites the finite set of PostgreSQL constructs the
  codebase uses: casts, `now()`/`interval` arithmetic, `jsonb`
  operators/functions (to SQLite `json_*`), `FOR UPDATE [SKIP LOCKED]`
  (dropped), advisory locks (satisfied by the transaction model),
  `COLLATE "C"` (SQLite's default binary order), `octet_length`,
  `substring(... FROM ? FOR ?)`, aggregate `FILTER`, `extract(epoch ...)`
  cursors, and the transaction-local export dedup table. Any untranslated
  construct fails fast with the offending SQL rather than misbehaving.
- Every write transaction is `BEGIN IMMEDIATE`, so the single writer provides
  at least the mutual exclusion that PostgreSQL row and advisory locks
  provide; WAL mode keeps concurrent readers unblocked. Worker
  `SKIP LOCKED` claims degrade to plain claims under that serialization.
- Parameters are bound in SQLite encodings (timestamps as sortable
  `YYYY-MM-DD HH:MM:SS.SSS` UTC text, UUIDs as text) and reads are coerced
  back to the Java types the PostgreSQL driver returns, keyed by declared
  column types (`timestamptz`, `boolean`, `uuid`).
- The schema is a consolidated baseline (`resources/migrations-sqlite/`)
  generated from the migrated PostgreSQL schema by
  `scripts/generate-sqlite-schema.py`, preserving checks, uniques, foreign
  keys, partial indexes, and the OAuth-epoch trigger (reimplemented as a
  SQLite trigger with identical bump/clamp semantics). Future migrations add
  matching numbered files for both dialects. Regex `CHECK` constraints are
  weakened to length checks; application validation remains authoritative.

## Verified today

`bash scripts/test-sqlite.sh` runs **66 integration namespaces against
temporary SQLite files** — 295 tests, ~3,200 assertions — and CI executes it
on every push. That is the great majority of the matrix: accounts and
sessions, app passwords, TOTP and passkeys, browser security and identity,
the OAuth suite (PAR, DPoP, interaction, tokens, sessions, permissions,
permission-set cache, cleanup, migration sessions), signed repositories,
CAR export and import, the firehose, blobs (upload, download, sync,
migration, empty), moderation and takedowns, handles and PLC signing and
submission, signing-key rotation, the service proxy, relay announcements,
account migration and activation, and the operator recovery CLI running as a
child process against the same file.

`pds.sqlite-backend-test` adds a SQLite-specific end-to-end slice:
invite-gated signup, duplicate-key rejection, session issue/refresh
rotation/replay revocation, record CRUD with pagination, repeated full CAR
exports over pooled connections, blob round trips, autoincrement event
sequencing, OAuth-epoch trigger advancement, deactivation/activation, and
interval-based expiry sweeps.

## Deliberately PostgreSQL-only

These are guarded with `fixture/postgres?` and state their reason in place:

- **Migration-history replays** (`take N` of the PostgreSQL chain) — the
  SQLite backend ships one consolidated baseline, so there is no partial
  history to replay: blob reference/revision backfills, handle-reservation
  backfill, repository-ownership upgrade, empty-blob upgrade.
- **Row-lock choreography** — tests that interleave two writers around a
  `FOR UPDATE` lock, or detect a blocked writer with `pg_blocking_pids`.
  SQLite serializes writers for the whole transaction, so the interleaving
  cannot be constructed: recovery-versus-browser, OAuth cleanup locks,
  signing-key rotation contention, temporary blob collection barriers.
- **`ALTER TABLE ... ADD CONSTRAINT` fault injection** — SQLite cannot add a
  constraint to an existing table, so the OAuth mint-failure rollback test
  stays PostgreSQL-only.
- **`pds.db-pool-test`** — asserts physical backend-connection identity with
  `pg_backend_pid()` and termination with `pg_terminate_backend()`. SQLite
  has no server process, so the namespace is not in the SQLite runner.

Also PostgreSQL-only: the backup/restore drill (it shells out to `pg_dump`),
the offline master-key rewrap CLI, and multi-process deployments. There is no
data migration between backends — pick one per deployment, and prefer
PostgreSQL for anything beyond a single small host.
