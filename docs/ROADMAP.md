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
4. **Repositories (core implemented; import/performance pending):** deterministic Merkle Search Tree, signed version-3 commits,
   atomic writes with swap checks, record CRUD, pagination, repository description,
   CAR import/export. Acceptance: reference implementation verifies our exports
   and we verify its exports, including deletion and concurrent writes.
5. **Identity and accounts (hosted did:web implemented):** DNS/HTTPS handle resolution, did:plc/did:web resolution,
   account provisioning, DID document publication, handle updates and key rotation.
   Acceptance: persisted identities resolve from a separate process; remote fetches
   have bounded sizes/timeouts and protection against SSRF.
6. **Sessions and account lifecycle (sessions/app passwords/email security/lifecycle/invites implemented):** password hashing, access/refresh tokens,
   refresh rotation/revocation, app passwords, invites, email verification/reset,
   account activation/deactivation/deletion and administrative authorization.
   Acceptance: expiry, replay, cross-account authorization and restart tests.
7. **OAuth:** authorization-server metadata, PAR, PKCE, DPoP, client metadata,
   consent, token binding and scoped permissions. Acceptance: reference clients
   complete login and invalid/replayed proofs fail.
8. **Blob APIs (buffered upload/download implemented):** streaming upload/download, limits, ownership, record references,
   list/missing blobs and garbage collection. Acceptance: binary round trips,
   interrupted upload cleanup and no cross-account access leaks.
9. **Sync and federation (queries, proofs and WebSocket firehose implemented; external relay pending):** durable ordered event log, sync queries, WebSocket
   subscribeRepos with replay/backpressure, relay notification and takedowns.
   Acceptance: relay consumes commits and reconnects without losing events.
10. **Service integration and migration:** authenticated service proxy, service
    auth tokens, account migration/import/export and PLC operations.
    Acceptance: an external client reads and writes through this PDS and an
    account migrates between this implementation and a reference PDS.
11. **Operations and conformance:** rate limits, quotas, structured logs, metrics,
    backup/restore, deployment/TLS, CI, compatibility matrix and end-to-end tests.
    Acceptance: restore a backup, replay sync, and run the complete compatibility
    suite against a pinned upstream revision.

## Architecture decisions

Current checkpoint: PostgreSQL persistence, configurable Cloudflare Worker email,
optional S3-compatible blob storage, optional Redis rate limits (in-memory default),
protocol codecs/identifiers, signed repositories, hosted did:web accounts,
rotating sessions, scoped app passwords, email recovery, schema-validated record APIs, CAR exports, and blobs are
implemented. See [the compatibility matrix](COMPATIBILITY.md) for exact coverage,
verification evidence, and limits. Full PDS compatibility is not achieved yet.

Next implementation series: extend Lexicon endpoint validation and administration,
remote identity/PLC, then streaming sync and relay conformance. OAuth
and service proxying remain required before a full client integration can be
claimed. Preserve atomic feature commits and test each protocol boundary.

- Plain Clojure namespaces, explicit dependencies, and pure functions for protocol
  logic; isolate network, clock, randomness and persistence at the edges.
- Jetty serves HTTP and WebSocket upgrades through Ring-shaped request/response
  maps. Blocking handlers and stream workers use owned virtual threads. A stream
  worker waits for each send callback with a deadline before producing another frame.
- PostgreSQL is the storage backend. Migrations are transactional, serialized by
  an advisory lock, and checked for changes using SHA-256. Integration tests use
  an isolated PostgreSQL cluster and disposable schemas.
- Track conformance honestly: unsupported features stay unsupported until their
  invariants are implemented. Health indicates process liveness, not federation
  readiness.

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
