# SQLite backend

When none of `PDS_DATABASE_URL`, `PDS_DATABASE_USER`, or
`PDS_DATABASE_PASSWORD` is configured, the server stores everything in one
SQLite file (`PDS_SQLITE_PATH`, default `data/clojure-pds.sqlite3`; WAL and
shared-memory sidecar files appear next to it). An explicit
`PDS_DATABASE_URL=jdbc:sqlite:<path>` selects it directly. **This backend is
experimental**: it passes a dedicated end-to-end slice plus the translation
tests below, not yet the full integration matrix that PostgreSQL passes.

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

`pds.sqlite-backend-test` runs on every suite execution against a temporary
SQLite file, covering: invite-gated signup, duplicate-key rejection, session
issue/refresh rotation/replay revocation, signed record CRUD with pagination,
`listRepos`/`listReposByCollection`, repeated full CAR exports over pooled
connections, staged blob upload/download round trips, `listMissingBlobs`,
autoincrement event sequencing, app passwords, email-token password reset,
OAuth-epoch trigger advancement, deactivation/activation, signing-key
reservation expiry, and interval-based token expiry.

`bash scripts/test-sqlite.sh` additionally runs eleven unmodified integration
namespaces from the PostgreSQL matrix under the SQLite fixture — repositories,
events, invites (including the concurrent final-use race), app passwords,
sessions, moderation, blob APIs, the firehose, account lifecycle, preferences,
and the email outbox — and CI executes it on every push. The OAuth token
suite also passes except for tests that inject faults with
`ALTER TABLE ... ADD CONSTRAINT`, which SQLite cannot express.

## Not yet verified on SQLite

The rest of the integration matrix (browser OAuth authorization, PLC
provisioning workers, repository imports/migration, browser security,
passkeys) still runs only against PostgreSQL; the fixture accepts `PDS_TEST_DATABASE_URL=jdbc:sqlite:` so
coverage can be expanded namespace by namespace. The offline master-key
rewrap CLI, the backup/restore drill, and multi-process deployments are
PostgreSQL-only. There is no data migration between backends — pick one per
deployment, and prefer PostgreSQL for anything beyond a single small host.
