# PDS compatibility checkpoint

Last verified: 2026-09-26. This is a development implementation, not a complete
AT Protocol PDS. Passing internal/fixture tests does not establish full network
interoperability. Run `bash scripts/test-redis.sh --with-s3` for the complete
Clojure suite and `node --test examples/email-worker/handler.test.mjs` for the Worker
contract. PostgreSQL tests use version 18.6 and the mise-pinned JDK 25.0.3.

## Implemented routes

All XRPC paths start with `/xrpc/`. Queries use GET (also HEAD); procedures use POST.

| Namespace | Methods | Scope |
| --- | --- | --- |
| `com.atproto.server` | `describeServer` | Service DID, hosted suffix when signup is open, blob limit |
| `com.atproto.server` | `createAccount` | New hosted did:web accounts; optional transactional invitation gate; no PLC, imports, or phone verification |
| `com.atproto.server` | `createInviteCode`, `createInviteCodes`, `getAccountInviteCodes` | Admin-issued codes, bounded batches, account listing and concurrent redemption limits; no automatic grants |
| `com.atproto.admin` | `disableInviteCodes`, `disableAccountInvites`, `enableAccountInvites` | Optional Basic admin credentials; code invalidation and future-grant policy |
| `com.atproto.admin` | `getAccountInfo`, `getSubjectStatus`, `updateSubjectStatus` | Private account inspection and repoRef account takedowns; activation state preserved; record/blob subjects pending |
| `com.atproto.server` | `createSession`, `getSession`, `refreshSession`, `deleteSession` | Primary/app-password sessions, JWT type separation, single-use refresh, revocation |
| `com.atproto.server` | `createAppPassword`, `listAppPasswords`, `revokeAppPassword` | One-time secrets, scoped sessions, privileged flag, metadata-only listing, immediate revocation |
| `com.atproto.server` | `deactivateAccount`, `activateAccount` | Primary-session lifecycle; inactive content is hidden, identity remains resolvable, durable account events |
| `com.atproto.server` | `requestAccountDelete`, `deleteAccount` | One-use email token plus primary password; credential removal, tombstone and durable S3 cleanup |
| `com.atproto.server` | `requestEmailConfirmation`, `confirmEmail` | Durable email outbox, expiring one-use confirmation |
| `com.atproto.server` | `requestEmailUpdate`, `updateEmail` | Proof to current confirmed address, old-token invalidation, optional email authentication factor |
| `com.atproto.server` | `requestPasswordReset`, `resetPassword` | Same public result for known/unknown addresses; reset revokes sessions |
| `com.atproto.identity` | `resolveHandle`, `resolveDid`, `resolveIdentity`, `refreshIdentity` | Hosted identities and remote DNS/HTTPS handles, did:web/PLC documents, bidirectional handle verification; uncached, bounded concurrency |
| `com.atproto.repo` | `createRecord`, `putRecord`, `deleteRecord`, `applyWrites` | Atomic signed commits; record/repo swap checks; batch maximum 200 |
| `com.atproto.repo` | `getRecord`, `listRecords`, `describeRepo` | Current records; keyset pagination and reverse order |
| `com.atproto.repo` | `uploadBlob` | Authenticated, maximum 5 MiB, account ownership |
| `com.atproto.sync` | `getRepo`, `getLatestCommit`, `getRepoStatus`, `listRepos` | Full CAR export and local repository metadata; no incremental export optimization |
| `com.atproto.sync` | `getBlocks`, `getRecord` | Repository-owned historical blocks; signed MST inclusion/absence proofs; rootless block CARs |
| `com.atproto.sync` | `subscribeRepos` | Binary CBOR WebSocket stream; durable replay, cursor errors, bounded sends/backlog, account filtering; external relay integration pending |
| `com.atproto.sync` | `getBlob`, `listBlobs` | Binary round trip and account-scoped keyset listing; `since` is rejected |

Additional routes: plain-text banner at `/`, liveness at `/xrpc/_health`, and hosted
identity documents at `/.well-known/did.json` and `/.well-known/atproto-did`.

## Protocol and storage evidence

- Identifier validators run the vendored handle, DID, NSID, record-key, TID, AT URI,
  and AT identifier syntax fixtures. One known upstream NSID discrepancy is
  documented in `test/fixtures/README.md`.
- Identity tests cover DNS precedence, conflicting claims, HTTPS fallback,
  reserved names, DID document identifier matching, first-handle selection,
  bidirectional verification, curve-point validation, and key/service selection.
  DNS socket tests exercise CNAME chains, TXT string concatenation, and removal of
  unrelated answer records. HTTP/TLS socket tests verify public-address checks,
  mixed DNS answers, redirect revalidation, byte bounds, cookie isolation, and
  certificate/hostname verification. PostgreSQL/HTTP tests cover hosted, remote,
  deleted and service identities. Resolution uses no application cache yet;
  `refreshIdentity` fetches fresh data. PLC directory responses are trusted over
  verified HTTPS; PLC operation-chain verification and provisioning remain pending.
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
  `bash scripts/test-conformance.sh` additionally uses pinned `@atproto/repo`
  0.10.14 to verify a signed export, inclusion/absence proofs, false-claim rejection,
  and rootless CARs obtained over HTTP. This covers repository serialization and
  proofs, not relay/client federation. CAR decoding verifies each block's content hash. Full repository import and
  untrusted MST traversal validation are not yet implemented.
- PostgreSQL migrations are locked, transactional, and checksummed. Tests verify
  rollback, persistence across connections, binary data, signed commits, atomic
  batches, concurrent swap conflicts, and isolation between accounts.
  A transactional ownership migration traverses retained commit/MST history;
  it does not grant ownership from arbitrary record links. Public block requests
  cannot retrieve blocks belonging only to a different account.
- Durable commit events contain signed CAR slices, mutation operations with previous
  CIDs, and previous revision/MST links. Upstream verification inverts mixed create,
  update, delete, and empty commits back to the previous root. Oversized proof
  batches roll back atomically. Legacy metadata-only events migrate to sync
  checkpoints; reactivation also emits a sync checkpoint. Signup emits identity,
  account, then commit events transactionally. Real WebSocket tests cover live delivery,
  exclusive cursor resume, server restart, window expiry, future/invalid cursors,
  availability filtering, connection limits and bounded send/backlog behavior.
  The pinned upstream CBOR decoder parses actual socket frames and verifies their
  signed repository. Jetty 12.1.13 supplies HTTP/WebSocket transport with owned
  virtual threads and bounded shutdown.
- Signing keys use AES-256-GCM with account DID as associated data. Passwords use
  Argon2id. Sessions are persisted and checked on requests; reset/replay/logout
  revocation is tested. Machine-generated app passwords use keyed digests; tests
  cover scope preservation through refresh, privilege-escalation rejection,
  account isolation, revocation, and password-reset cleanup.
- Email tests verify the HTTP contract, retry classes, backoff, concurrent claims,
  expired leases, attempt exhaustion, transactional rollback, and payload cleanup.
  Worker tests use mocked bindings; deployment and provider delivery are unverified.
- Blob bytes can use PostgreSQL or configurable S3-compatible storage. An upgrade
  test preserves existing blobs. Moto-backed HTTP tests cover binary round trips,
  ownership, duplicate uploads, missing objects and failed PUT rollback; fault
  tests cover corrupted content. Live provider behavior remains unverified.
- A child-JVM test runs `pds.main`, checks HTTP health, and verifies graceful shutdown,
  including optional S3/Redis clients when the combined suite enables them.
- Rate limiting defaults to bounded in-memory counters. Optional Redis uses atomic
  expiring counters; real Redis 8.2.3 tests cover concurrent budgets shared by two
  clients, expiry, reopen, isolation and failures. Forwarding headers remain ignored.

## Remaining work for a full PDS

1. Expand the Lexicon catalog, dynamic schema resolution, and complete input/output
   validation against pinned official endpoint lexicons.
2. did:plc provisioning, PLC operation-chain verification, bounded identity caching,
   handle updates, signing/rotation key lifecycle, and migration.
3. Remaining administrative APIs, record/blob takedowns, and broader account recovery controls.
4. OAuth authorization server: metadata, PAR, PKCE, DPoP, client metadata/consent,
   refresh behavior, permission sets and scopes.
5. Relay notification and external relay interoperability, identity-change events,
   event retention/compaction, and remaining record/blob takedown semantics.
6. Service auth JWTs, authenticated proxying to AppViews/labelers, and external
   client/relay end-to-end tests.
7. Repository import, blob missing/list-since behavior, garbage collection,
   incremental MST mutation, streaming, quotas and bulk blob-backend migration.
8. PostgreSQL connection pooling, account-specific abuse controls, metrics/logging, CORS,
   operational deployment/TLS and backup/restore drills. Push CI is configured;
   its first GitHub execution still requires a push.

## Source pins

- Imported conformance fixtures: revision recorded in `test/fixtures/README.md`.
- Endpoint definitions consulted at upstream atproto revision
  `7a857989751ae31518509d69ab7194a922064f3d`.
- Protocol pages are moving specifications; see the roadmap's primary references.
