# clojure-pds

An AT Protocol Personal Data Server in Clojure with PostgreSQL persistence
and a zero-configuration SQLite fallback.
**In development: not yet a fully federating PDS.**

Implemented: hosted did:web/did:plc accounts, sessions and app passwords, email
security flows, OAuth with DPoP and scoped permissions, signed repositories with
verified CAR import/export, binary blobs (PostgreSQL or S3), a WebSocket firehose,
an authenticated streaming service proxy, account migration primitives, and
administrative/moderation APIs — 71 XRPC endpoints validated against pinned
upstream Lexicons. See the [compatibility matrix](docs/COMPATIBILITY.md) for
exact coverage and the [roadmap](docs/ROADMAP.md) for remaining work.

## Quick start

```sh
mise trust && mise install

# Generate ONCE and save securely; reuse this key across restarts.
export PDS_MASTER_KEY="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"

# Zero configuration: stores everything in data/clojure-pds.sqlite3.
mise exec -- clojure -M:run

curl http://127.0.0.1:3000/xrpc/_health
```

For PostgreSQL (recommended beyond a single small host), export
`PDS_DATABASE_PASSWORD` and `docker compose up -d postgres` first; any
`PDS_DATABASE_*` variable switches the backend.

See [get started](docs/get-started.md) for prerequisites, master-key
handling and the account UI, and [the configuration reference](docs/CONFIGURATION.md)
for every environment variable ([.env.example](.env.example) is a template).

Run the tests with `bash scripts/test-postgres.sh`; see
[development](docs/DEVELOPMENT.md) for the full test matrix, CI and the REPL.

## Documentation

Browse these pages as a website at
<https://clojure-pds-docs.tsirysndr.deno.net/> (`docs/` doubles as a Lume
static site; see [docs/README.md](docs/README.md)).

### Setup and operations

- [Get started](docs/get-started.md) — install, run, master key, account UI
- [Installation](docs/installation.md) — toolchain, source, Docker image, Nix flake
- [Deployment](docs/DEPLOYMENT.md) — VPS checklist, Docker image, Nix flake
- [Configuration](docs/CONFIGURATION.md) — environment variables and migrations
- [Development](docs/DEVELOPMENT.md) — test suites, CI, REPL
- [Database pool](docs/DATABASE.md) — connection pool lifecycle and limits
- [SQLite backend](docs/SQLITE.md) — the single-file fallback, dialect layer, scope
- [Backup and restore](docs/BACKUP.md) — checksummed archives and the recovery drill
- [Master key rotation](docs/MASTER-KEY.md) — offline re-encryption of stored secrets
- [S3 blob storage](docs/S3.md) — optional S3-compatible backend
- [Redis rate limits](docs/REDIS.md) — shared counters and write budgets
- [Admin and invites](docs/ADMIN.md) — invite codes, account administration, takedowns
- [Moderation](docs/MODERATION.md) — account, record and blob takedown semantics
- [Administrative account recovery](docs/ADMIN-ACCOUNTS.md) — operator credential recovery

### Identity and accounts

- [Hosted identities](docs/IDENTITY.md) — DID methods, resolution, PLC signup, handle updates, owner-signed PLC operations
- [Identity cache](docs/IDENTITY-CACHE.md) — public resolution freshness and bounds
- [Sessions and lifecycle](docs/ACCOUNTS.md) — sessions, app passwords, email flows, deactivation, deletion
- [Account security](docs/ACCOUNT-SECURITY.md) — TOTP, passkeys and the browser UI
- [OAuth](docs/OAUTH.md) — authorization server, DPoP, scoped permissions
- [Key rotation](docs/KEY-ROTATION.md) — managed control/signing-key rotation
- [PLC recovery keys](docs/PLC-RECOVERY-KEYS.md) — account-held recovery keys
- [PLC recovery](docs/PLC-RECOVERY.md) — externally signed recovery forks
- [PLC reconciliation](docs/PLC-RECONCILIATION.md) — adopting external directory changes

### Data, federation and services

- [Account migration](docs/MIGRATION.md) — destination preparation, repository import, blob transfer, service auth
- [Repository exports](docs/REPO-EXPORT.md) — streaming CAR export limits
- [Repository imports](docs/REPO-IMPORT.md) — disk-staged verified imports
- [Service proxy](docs/PROXY.md) — authenticated streaming proxy to AppViews/labelers
- [Relay announcements](docs/RELAY.md) — opt-in durable relay notification
- [Preferences](docs/PREFERENCES.md) — private Bluesky preferences and transfer
- [Lexicon resolution](docs/LEXICON-RESOLUTION.md) — dynamic schema validation
- [Email delivery](docs/EMAIL.md) — Cloudflare Worker outbox contract

### Status

- [Compatibility matrix](docs/COMPATIBILITY.md) — implemented routes and verification evidence
- [Current limits](docs/LIMITS.md) — validation, repository, firehose, blob and rate-limit bounds
- [Roadmap](docs/ROADMAP.md) — milestones and architecture decisions

## License

No project license has been selected. Vendored conformance fixtures retain their
upstream CC0 license; vendored Lexicons retain their upstream MIT/Apache notices.
