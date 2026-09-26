# PDS compatibility checkpoint

Last verified: 2026-09-26. This is a development implementation, not a complete
AT Protocol PDS. Passing internal/fixture tests does not establish full network
interoperability. Current tests with local S3 and Redis: 47 Clojure tests, 1,345 assertions; one Worker
contract test. PostgreSQL tests used version 18.6 and the mise-pinned JDK 25.0.3.

## Implemented routes

All XRPC paths start with `/xrpc/`. Queries use GET (also HEAD); procedures use POST.

| Namespace | Methods | Scope |
| --- | --- | --- |
| `com.atproto.server` | `describeServer` | Service DID, hosted suffix when signup is open, blob limit |
| `com.atproto.server` | `createAccount` | New hosted did:web accounts; no PLC, imports, invites, or phone verification |
| `com.atproto.server` | `createSession`, `getSession`, `refreshSession`, `deleteSession` | Primary-password sessions, JWT type separation, single-use refresh, revocation |
| `com.atproto.server` | `requestEmailConfirmation`, `confirmEmail` | Durable email outbox, expiring one-use confirmation |
| `com.atproto.server` | `requestPasswordReset`, `resetPassword` | Same public result for known/unknown addresses; reset revokes sessions |
| `com.atproto.identity` | `resolveHandle` | Hosted accounts only |
| `com.atproto.repo` | `createRecord`, `putRecord`, `deleteRecord`, `applyWrites` | Atomic signed commits; record/repo swap checks; batch maximum 200 |
| `com.atproto.repo` | `getRecord`, `listRecords`, `describeRepo` | Current records; keyset pagination and reverse order |
| `com.atproto.repo` | `uploadBlob` | Authenticated, maximum 5 MiB, account ownership |
| `com.atproto.sync` | `getRepo`, `getLatestCommit`, `getRepoStatus`, `listRepos` | Full CAR export and local repository metadata; no incremental export optimization |
| `com.atproto.sync` | `getBlob`, `listBlobs` | Binary round trip and account-scoped keyset listing; `since` is rejected |

Additional routes: plain-text banner at `/`, liveness at `/xrpc/_health`, and hosted
identity documents at `/.well-known/did.json` and `/.well-known/atproto-did`.

## Protocol and storage evidence

- Identifier validators run the vendored handle, DID, NSID, record-key, TID, AT URI,
  and AT identifier syntax fixtures. One known upstream NSID discrepancy is
  documented in `test/fixtures/README.md`.
- Canonical CBOR encoding and CID generation match upstream bytes/hashes. Decoding
  rejects noncanonical forms, invalid UTF-8, duplicate keys, floats, trailing data,
  and oversized/deep blocks. JSON request depth is bounded before parsing.
- Record schema validation uses 33 pinned, checksummed Lexicons (17 record roots).
  Tests cover upstream record fixtures, required/nullable fields, nested unions,
  references, UTF-8/grapheme limits, blobs, string formats, and key rules. Unknown
  schemas remain writable by default; explicit validation requires a known schema.
  Invalid batches roll back records, blocks and commits. CID string formats are
  restricted to the blessed AT Protocol CID set; see fixture notes.
- P-256 and secp256k1 signatures enforce the 64-byte low-S format using Bouncy Castle;
  verification runs upstream signature vectors. Repositories currently sign with P-256.
- MST height/prefix vectors and before/after commit root fixtures match upstream.
  CAR decoding verifies each block's content hash. Full repository import and
  untrusted MST traversal validation are not yet implemented.
- PostgreSQL migrations are locked, transactional, and checksummed. Tests verify
  rollback, persistence across connections, binary data, signed commits, atomic
  batches, concurrent swap conflicts, and isolation between accounts.
- Signing keys use AES-256-GCM with account DID as associated data. Passwords use
  Argon2id. Sessions are persisted and checked on requests; reset/replay/logout
  revocation is tested.
- Email tests verify the HTTP contract, retry classes, backoff, concurrent claims,
  expired leases, attempt exhaustion, transactional rollback, and payload cleanup.
  Worker tests use mocked bindings; deployment and provider delivery are unverified.
- Blob bytes can use PostgreSQL or configurable S3-compatible storage. An upgrade
  test preserves existing blobs. Moto-backed HTTP tests cover binary round trips,
  ownership, duplicate uploads, missing objects and failed PUT rollback; fault
  tests cover corrupted content. Live provider behavior remains unverified.
- A child-JVM test runs `pds.main`, checks HTTP health, and verifies graceful shutdown.
- Rate limiting defaults to bounded in-memory counters. Optional Redis uses atomic
  expiring counters; real Redis 8.2.3 tests cover concurrent budgets shared by two
  clients, expiry, reopen, isolation and failures. Forwarding headers remain ignored.

## Remaining work for a full PDS

1. Expand the Lexicon catalog, dynamic schema resolution, and complete input/output
   validation against pinned official endpoint lexicons.
2. did:plc provisioning, remote DID/handle resolution with SSRF protection,
   handle updates, signing/rotation key lifecycle, and migration.
3. App passwords, invites, account deletion/deactivation/reactivation, email
   updates, administrative APIs, and broader account recovery controls.
4. OAuth authorization server: metadata, PAR, PKCE, DPoP, client metadata/consent,
   refresh behavior, permission sets and scopes.
5. Sync block/proof APIs, commit event payloads, WebSocket subscribeRepos with
   cursor replay/backpressure, relay notification, account/identity events and
   takedown semantics. `repo_events` currently records commit metadata only.
6. Service auth JWTs, authenticated proxying to AppViews/labelers, and external
   client/relay end-to-end tests.
7. Repository import, blob missing/list-since behavior, garbage collection,
   incremental MST mutation, streaming, quotas and bulk blob-backend migration.
8. PostgreSQL connection pooling, account-specific abuse controls, metrics/logging, CORS,
   operational deployment/TLS, backup/restore drills and CI.

## Source pins

- Imported conformance fixtures: revision recorded in `test/fixtures/README.md`.
- Endpoint definitions consulted at upstream atproto revision
  `7a857989751ae31518509d69ab7194a922064f3d`.
- Protocol pages are moving specifications; see the roadmap's primary references.
