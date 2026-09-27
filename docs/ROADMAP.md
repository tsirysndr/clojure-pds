# Implementation roadmap

The objective is a complete, interoperable AT Protocol PDS. Completion means
verified behavior against the official lexicons, protocol fixtures, and a reference
client/relay, not merely the presence of endpoint names. AppView indexing, feeds,
and moderation services are separate services; the PDS will proxy to them.

## Sequence

Each numbered milestone is split into atomic feature commits. Keep every commit
runnable and update this document as features land.

1. **Foundation (complete):** Clojure CLI project, tests, validated environment
   configuration, HTTP lifecycle, XRPC errors, health and server discovery.
   Acceptance: start locally, query discovery, reject invalid configuration,
   test responses over a real socket, and shut down cleanly.
2. **Protocol primitives (implemented; conformance expansion ongoing):** NSID, handle, DID, AT URI, record key and TID validation;
   canonical DRISL/DAG-CBOR encoding, CID, CAR, supported signing algorithms.
   Acceptance: upstream valid/invalid fixtures, deterministic encoding and
   cryptographic vectors. Do not substitute JSON hashes for repository CIDs.
3. **Durable storage (in progress):** PostgreSQL migrations, transactions, account metadata, key
   storage, content-addressed blocks and blobs, restart/recovery tests.
4. **Repositories (core and verified CAR import/export implemented; streaming/performance pending):** deterministic Merkle Search Tree, signed version-3 commits,
   atomic writes with swap checks, record CRUD, pagination, repository description,
   CAR import/export. Acceptance: reference implementation verifies our exports
   and we verify its exports, including deletion and concurrent writes.
5. **Identity and accounts (signup, resolution, handle updates and managed control/signing-key rotation implemented; migration conformance pending):** DNS/HTTPS handle resolution, did:plc/did:web resolution,
   account provisioning, DID document publication, handle updates and key rotation.
   Acceptance: persisted identities resolve from a separate process; remote fetches
   have bounded sizes/timeouts and protection against SSRF.
6. **Sessions and account lifecycle (sessions/app passwords/email security/lifecycle/invites implemented):** password hashing, access/refresh tokens,
   refresh rotation/revocation, app passwords, invites, email verification/reset,
   account activation/deactivation/deletion and administrative authorization.
   [Administrative recovery](ADMIN-ACCOUNTS.md) supports password/email updates and
   account deletion, with session invalidation, factor preservation and durable
   object cleanup. The local all-authenticator-loss procedure atomically replaces
   the password, removes factors and invalidates access, with security-version
   checks and retry-safe operator receipts.
   Optional [authenticator security](ACCOUNT-SECURITY.md): TOTP storage, confirmation,
   recovery codes and legacy/OAuth verification are implemented, as are WebAuthn
   passkey registration/assertion primitives. Secure browser enrollment/removal and
   passkey login integration are implemented; hardware and complete browser ceremonies remain pending.
   Acceptance: expiry, replay, cross-account authorization and restart tests.
7. **OAuth (discovery, browser authorization, tokens/revocation, direct and transitional resource permissions implemented):** authorization-server metadata, PAR, PKCE, DPoP, client metadata,
   consent, token binding and scoped permissions. Acceptance: reference clients
   complete login and invalid/replayed proofs fail. Local upstream Node client flows
   cover signup, login, writes, refresh and revocation. Email/repository/identity
   management scopes and [authenticated Lexicon resolution](LEXICON-RESOLUTION.md)
   are implemented, including permission-set expansion, shared verified schema
   caching, fixed access-token permissions, refresh updates and localized consent.
   OAuth inactive migration and deployed interoperability remain pending. Account
   status permissions await defined semantics in the published permission spec.
8. **Blob APIs (buffered transfer, references, missing/since listing and temporary/reference cleanup implemented):** streaming upload/download, limits, ownership, record references,
   list/missing blobs and garbage collection. Acceptance: binary round trips,
   interrupted upload cleanup and no cross-account access leaks.
9. **Sync and federation (queries, proofs, WebSocket firehose and relay announcements implemented; external relay pending):** durable ordered event log, sync queries, WebSocket
   subscribeRepos with replay/backpressure, relay notification and takedowns.
   Acceptance: relay consumes commits and reconnects without losing events.
   [Relay discovery](RELAY.md) is opt-in, with durable per-relay scheduling,
   bounded HTTPS delivery, retries and cross-process leases.
   [Administrative takedowns](MODERATION.md) cover accounts, records and blobs;
   content flags preserve signed repository data and isolate each account.
10. **Service integration and migration (authenticated buffered proxy, service tokens/replay protection, destination preparation, public data/private preference transfer and verified activation implemented):** authenticated service proxy, service
    auth tokens, account migration/import/export and PLC operations.
    Acceptance: an external client reads and writes through this PDS and an
    account migrates between this implementation and a reference PDS.
    [Private preferences](PREFERENCES.md) support inactive transfer, primary-only
    personal details, RPC-scoped OAuth access, status counts and deletion cleanup.
11. **Operations and conformance:** rate limits, quotas, structured logs, metrics,
    backup/restore, deployment/TLS, CI, compatibility matrix and end-to-end tests.
    Acceptance: restore a backup, replay sync, and run the complete compatibility
    suite against a pinned upstream revision.
    The [logical database recovery drill](BACKUP.md) restores credentials, private
    state, blobs and signed repositories into a new database, then verifies HTTP
    behavior and WebSocket replay. Production PITR and external object-store
    disaster recovery remain pending.

## Architecture decisions

Current checkpoint: PostgreSQL persistence, configurable Cloudflare Worker email,
optional S3-compatible blob storage, optional Redis rate limits (in-memory default),
protocol codecs/identifiers, signed repositories, hosted did:web and did:plc accounts,
rotating sessions, scoped app passwords, email recovery, schema-validated record APIs, CAR exports, and blobs are
implemented. See [the compatibility matrix](COMPATIBILITY.md) for exact coverage,
verification evidence, and limits. Full PDS compatibility is not achieved yet.

Next implementation series: remaining identity/key lifecycle, migration,
administration, and relay conformance.
Implemented endpoints now have pinned input validation and observed output/event
schema checks with required endpoint/message coverage. Authenticated buffered
service proxying is implemented; OAuth and external service interoperability
remain required before a full client integration can be claimed. Preserve atomic feature commits and test each protocol boundary.

- Plain Clojure namespaces, explicit dependencies, and pure functions for protocol
  logic; isolate network, clock, randomness and persistence at the edges.
- Jetty serves HTTP and WebSocket upgrades through Ring-shaped request/response
  maps. Blocking handlers and stream workers use owned virtual threads. A stream
  worker waits for each send callback with a deadline before producing another frame.
- PostgreSQL is the storage backend. Migrations are transactional, serialized by
  an advisory lock, and checked for changes using SHA-256. Integration tests use
  an isolated PostgreSQL cluster and disposable schemas. The server uses a
  [bounded connection pool](DATABASE.md), with configurable capacity and borrow
  deadlines, transaction cleanup and startup/shutdown ownership.
- Remote identity fetches use a shared Jetty client with HTTPS certificate and
  hostname verification, validated public socket addresses, bounded responses
  and deadlines, and manual redirect validation. Cookies and transparent
  decompression are disabled. Tests exercise real HTTP/TLS sockets, redirects,
  mixed DNS answers and certificate failures before identity endpoints use it.
- Pure PLC operations support both curves, legacy genesis verification, canonical
  chain verification and audit recovery rules. Tests exchange signed operations
  with the pinned reference library. Directory submission and durable provisioning
  verify the audit log, use encrypted per-account rotation keys, and activate only
  after confirmation. Handle updates also use durable verified operations and
  transactional identity events, preserving web DID hostnames and unrelated PLC
  fields. Recommended public DID credentials and email-authorized PLC operation
  signatures are exposed for migration. Signed submissions enforce local credential
  constraints and reconcile through the durable identity queue.
  [Managed control/signing-key rotation](KEY-ROTATION.md) uses the same durable queue,
  preserves recovery priority, and installs encrypted replacement keys only after
  confirmation. Signing-key rotation re-signs the existing MST root, preserves records
  and emits identity/sync checkpoints; hosted web rotation is atomic locally.
  [Offline master-key rewrapping](MASTER-KEY.md) preserves encrypted secrets and
  queued operations, invalidates legacy credentials, and prevents mismatched-key
  startup. [PLC conflict reconciliation](PLC-RECONCILIATION.md) explicitly adopts
  compatible verified history, preserves required queued keys, proves safe queue
  supersession and fences stale workers with retry-safe public receipts.
  [Account-held recovery-key management](PLC-RECOVERY-KEYS.md) supports ordered
  replacement and removal, retains the PDS key, and records confirmed changes
  through the durable queue. Recovery-fork submission and migration conformance
  remain pending. General PLC resolution verifies audit history and derives the current document, including
  for hosted accounts. It does not fall back to a stale local snapshot. Directory
  timestamps and history completeness/freshness remain trusted assertions.
- Public identity endpoints use a bounded, configurable process-local cache with
  explicit refresh and fencing of older in-flight lookups. Hosted identity checks
  precede cache reads; internal security-sensitive resolvers remain uncached.
  See [cache freshness and limits](IDENTITY-CACHE.md).
- Track conformance honestly: unsupported features stay unsupported until their
  invariants are implemented. Health indicates process liveness, not federation
  readiness.
- Explicit record validation resolves authenticated remote schemas and referenced
  definitions through a bounded compiler and process-local cache. Optimistic
  validation uses bundled/cached graphs; skipped validation still enforces the
  data model. Network resolution holds no account/repository transaction, and
  write authorization is rechecked afterward. See [dynamic validation](LEXICON-RESOLUTION.md#dynamic-record-writes).

## Primary references

Consulted 2026-09-26; upstream main/spec pages are moving targets. Imported
fixtures are pinned in `test/fixtures/README.md`; endpoint reference revision is
recorded in `COMPATIBILITY.md`.

- [Protocol overview](https://atproto.com/guides/overview)
- [XRPC](https://atproto.com/specs/xrpc)
- [Repository format](https://atproto.com/specs/repository)
- [Data model](https://atproto.com/specs/data-model)
- [Cryptography](https://atproto.com/specs/cryptography)
- [DID](https://atproto.com/specs/did) and [handle](https://atproto.com/specs/handle)
- [OAuth](https://atproto.com/specs/oauth)
- [Sync](https://atproto.com/specs/sync)
- [Official lexicons](https://github.com/bluesky-social/atproto/tree/main/lexicons)
- [Reference PDS](https://github.com/bluesky-social/atproto/tree/main/packages/pds)
