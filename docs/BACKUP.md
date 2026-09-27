# PostgreSQL backup and restore

`scripts/database-backup.py` creates a whole-database custom-format archive,
checks its SHA-256 digest and restores it into an empty database. It requires
Python 3.11+ and `pg_dump`, `pg_restore` and `psql`. Set `PG_BIN` to their directory
if they are not on PATH. Use tools compatible with your PostgreSQL server; the
local drill and CI use PostgreSQL 18. These commands do not load `.env` or convert
`PDS_DATABASE_URL` into a libpq connection string.

Configure the standard libpq environment separately. `PGDATABASE` is required and
must be an explicit database name containing letters, digits, underscores or
hyphens, starting with a letter or underscore (maximum 63 characters). Use
`PGPASSFILE` for a protected password file; do not put credentials in command
arguments. Preserve your deployment's TLS settings, such as `PGSSLMODE` and
`PGSSLROOTCERT`.

```sh
export PGHOST=127.0.0.1
export PGPORT=5432
export PGUSER=pds
export PGDATABASE=clojure_pds
export PGPASSFILE=/secure/path/postgresql.pgpass
# Optional, for the local Homebrew installation:
export PG_BIN=/opt/homebrew/opt/postgresql@18/bin

python3 scripts/database-backup.py backup /secure/backups/pds-2026-09-27
python3 scripts/database-backup.py verify /secure/backups/pds-2026-09-27
```

The parent backup directory must exist. A backup directory is created exclusively
with mode 0700, and files use mode 0600. Existing directories are never overwritten.
`database.dump` contains all database tables and inline PostgreSQL blob bytes.
`manifest.json` records the dump size, checksum, creation time and tool version.
The manifest is written last, after successful dump completion and file syncing;
a directory without it is incomplete. Failed backups retain their partial files
for diagnosis; retry into a new directory. The checksum detects accidental damage,
not an attacker who can replace both files, and does not prove that a restore
works. Only restore archives from trusted sources: PostgreSQL archives contain
executable database definitions.

Archives contain sensitive account data, password hashes, tokens, encrypted
private keys and email payloads. Encrypt backups at rest and in transit, restrict
access, and retain copies outside the PDS host. The script does not provide
encryption, scheduling, retention, off-site storage or PostgreSQL WAL archiving.

## What must be saved separately

- The exact `PDS_MASTER_KEY`. Keep a recoverable encrypted copy separate from the
  database archive. Losing it prevents recovery of repository/PLC signing keys
  and other encrypted secrets. Restoring a database with a different key is not
  a supported key rotation.
- Deployment configuration: public URL, user domain, DID method, database schema,
  email Worker settings, S3 configuration, OAuth/identity configuration and other
  secrets. Do not publish them alongside a backup manifest.
- Database roles, privileges, tablespaces, database-level settings, encoding and
  collation. The restore deliberately uses the restoring role as object owner
  and does not restore ACLs or tablespace placement. Provision the destination
  role/database and reapply the intended least-privilege grants separately.
- For S3, all object bytes at the bucket/key locators stored in PostgreSQL, including
  objects retained for pending operations. PostgreSQL stores those locators, not
  the external bytes. Bucket versioning, replication and object backups require
  a provider-specific recovery plan. Changing `PDS_S3_BUCKET` does not rewrite
  existing locators to a replacement bucket.

For a coordinated database/S3 recovery point, stop all PDS processes and writers,
including blob deletion workers, while capturing the database and external object
snapshot. A logical database snapshot alone cannot prevent a later S3 deletion
from removing bytes needed by that snapshot. Retention/versioning must preserve
the corresponding objects for at least the database backup's lifetime. Redis
rate counters are transient and do not need restoration.

## Restore and cutover

Keep the destination offline and stop all PDS processes and other writers. Create
a fresh, empty database with the required owner, encoding and locale (for example,
using `createdb --template=template0` with your deployment's options). Do not point
the tool at an existing running PDS.

```sh
export PGDATABASE=clojure_pds_recovery
python3 scripts/database-backup.py restore /secure/backups/pds-2026-09-27
```

Restore verifies the archive before connecting, rejects existing user schemas or
objects, and runs `pg_restore` in one transaction with exit-on-error. It never
uses `--clean` or `--create`. Existing data is not overwritten; a failed SQL/data
restore rolls back. Keep the backup directory immutable during restore. The
destination must remain dedicated to this operation; the preflight check is not
a lock against another administrator creating objects concurrently.

Before starting the normal server, recover the master key and configuration,
restore S3 objects if applicable, and point `PDS_DATABASE_URL` at the recovered
database. Run `mise exec -- clojure -M:migrate` to check/apply migrations. Validate
account login, private settings, blob retrieval, signed repository export and
sync replay in an isolated environment. Verify remote DID/PLC credentials still
match the recovered local keys, especially if keys or PDS locations changed
after the backup. Keep email, relay and external provisioning activity isolated
until recovery is ready: normal server startup resumes its durable workers.

This restores state at the saved snapshot. It cannot recover later writes or
external identity changes. In particular, consumers may already have observed
higher firehose sequence numbers or newer commits than the restored snapshot.
Do not assume cursor continuity after rolling production back to an older backup;
coordinate resynchronization with consumers. Production point-in-time recovery,
relay recovery after history rollback, and provider-level S3 recovery drills remain
unverified. Avoid running source and restored PDS instances simultaneously for
the same identities during cutover.

## Automated recovery drill

```sh
PDS_TEST_BACKUP=true PDS_TEST_UPSTREAM=true bash scripts/test-redis.sh --with-s3
```

The opt-in drill requires a disposable loopback PostgreSQL server and a role that
can create databases. It creates two random databases, runs the actual backup and
restore commands, compares every restored table, and then exercises account login,
existing sessions, refresh rotation/replay rejection, private preferences,
moderation state, binary blobs, signed writes and WebSocket replay. The pinned
upstream verifier checks the restored stream's repository signatures. New events
must advance past the saved sequence numbers. Failure cases cover refusing an
existing database/backup, checksum corruption and transactional rollback of a
damaged archive with a matching checksum. Temporary databases and archive files
are removed afterward.

With the S3 emulator, the same drill verifies restored metadata can retrieve
objects at their original locators. It does not simulate loss/recovery of the
provider's object storage. CI enables the drill and installs PostgreSQL 18 client
tools. The CI job itself still needs a push to run on GitHub.

PostgreSQL documents snapshot and archive behavior in
[pg_dump](https://www.postgresql.org/docs/18/app-pgdump.html) and transactional
restore options in [pg_restore](https://www.postgresql.org/docs/18/app-pgrestore.html).
