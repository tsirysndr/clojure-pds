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
| `com.atproto.server` | `createAccount` | Configurable signup and service-authenticated destination preparation for existing web/PLC DIDs; inactive imports with new local keys; final activation and phone verification pending |
| `com.atproto.server` | `createInviteCode`, `createInviteCodes`, `getAccountInviteCodes` | Admin-issued codes, bounded batches, account listing and concurrent redemption limits; no automatic grants |
| `com.atproto.admin` | `disableInviteCodes`, `disableAccountInvites`, `enableAccountInvites` | Optional Basic admin credentials; code invalidation and future-grant policy |
| `com.atproto.admin` | `getAccountInfo`, `getSubjectStatus`, `updateSubjectStatus` | Private account inspection and repoRef account takedowns; activation state preserved; record/blob subjects pending |
| `com.atproto.server` | `createSession`, `getSession`, `refreshSession`, `deleteSession` | Primary/app-password sessions, JWT type separation, single-use refresh, revocation |
| `com.atproto.server` | `getServiceAuth` | Repository-key JWTs, exact audience/service reference, method and expiration checks, primary/app-password privilege policy; authenticated proxying pending |
| `com.atproto.server` | `createAppPassword`, `listAppPasswords`, `revokeAppPassword` | One-time secrets, scoped sessions, privileged flag, metadata-only listing, immediate revocation |
| `com.atproto.server` | `deactivateAccount`, `activateAccount` | Primary-session lifecycle; inactive content is hidden, identity remains resolvable, durable account events |
| `com.atproto.server` | `requestAccountDelete`, `deleteAccount` | One-use email token plus primary password; credential removal, tombstone and durable S3 cleanup |
| `com.atproto.server` | `requestEmailConfirmation`, `confirmEmail` | Durable email outbox, expiring one-use confirmation |
| `com.atproto.server` | `requestEmailUpdate`, `updateEmail` | Proof to current confirmed address, old-token invalidation, optional email authentication factor |
| `com.atproto.server` | `requestPasswordReset`, `resetPassword` | Same public result for known/unknown addresses; reset revokes sessions |
| `com.atproto.identity` | `resolveHandle`, `resolveDid`, `resolveIdentity`, `refreshIdentity` | Hosted identities and remote DNS/HTTPS handles, did:web/PLC documents, bidirectional handle verification; uncached, bounded concurrency |
| `com.atproto.identity` | `updateHandle`, `getRecommendedDidCredentials` | Hosted/custom handles, durable audited PLC changes, stable web DID hostnames, public migration credentials; migration itself pending |
| `com.atproto.identity` | `requestPlcOperationSignature`, `signPlcOperation` | Primary session plus one-use emailed proof; verified latest audit, partial credential overrides; returns an operation without submitting it |
| `com.atproto.identity` | `submitPlcOperation` | Credential constraints, authorized successor signatures, durable directory reconciliation and atomic identity events; prepared destination identities supported; recovery forks pending |
| `com.atproto.repo` | `createRecord`, `putRecord`, `deleteRecord`, `applyWrites` | Atomic signed commits; record/repo swap checks; batch maximum 200 |
| `com.atproto.repo` | `getRecord`, `listRecords`, `describeRepo` | Current records; keyset pagination and reverse order |
| `com.atproto.repo` | `importRepo` | Primary session, complete signed v3 CAR, atomic record replacement and destination re-signing; active sync checkpoint or private inactive import; buffered 64 MiB limit |
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
  `refreshIdentity` fetches fresh data. PLC resolution, including hosted accounts,
  verifies the signed audit log and derives its canonical DID document. Tests cover
  recovery/nullification, forged metadata, tombstones, and external migration
  replacing a stale local key/endpoint. Invalid audits fail without local fallback.
  Directory timestamps, completeness and freshness remain trusted over verified
  HTTPS. A read-only live lookup of `bsky.app` passed through this resolver.
- PLC operations use canonical DAG-CBOR, string CID links, deterministic low-S
  signatures, a 7500-byte specification bound, and strict unpadded base64url.
  Tests cover modern and legacy genesis hashes, old-key authorization of updates,
  rotation, tombstones, and recovery with higher-priority keys within 72 hours.
  Audit verification recomputes nullification flags; directory timestamps remain
  trusted assertions. Pinned `@did-plc/lib` 0.0.4 verifies our chains and recovery,
  and Clojure verifies reference-generated chains on both curves. Generation uses
  `@atproto/crypto` 0.5.5 because the PLC package's old P-256 signer predates mandatory
  low-S signatures. A read-only smoke check also verified the four-operation public
  audit log for `did:plc:z72i7hdynmk6r22z27h6tvur`; no operation was submitted.
- The PLC directory client submits bounded JSON over verified HTTPS without
  redirects, then checks the complete signed audit log before reporting success.
  A timeout or error after acceptance is reconciled by operation CID; an already
  confirmed operation is not submitted again. TLS fixture tests cover dropped
  responses, server errors after acceptance, false success responses, rejected
  submissions, changed heads and malformed audits. Audit responses are limited to
  4 MiB and 10,000 operations.
- PLC signup reserves handle/email/invite use with encrypted, distinct repository
  and rotation keys before directory submission. Lease-based workers persist
  retries and sanitize failures. Pending accounts cannot authenticate or expose
  repositories/identities; activation, the first signed commit, three firehose
  events and confirmation email commit atomically after audit verification.
  Integration tests use a TLS directory fixture and PostgreSQL to exercise normal
  signup, recovery-key priority, local rollback after remote acceptance, worker
  reconstruction, expired/concurrent leases, invite rollback, credential races,
  and private-key cleanup on deletion. These tests do not register a real public
  identity or establish full external-client/relay interoperability.
- Canonical CBOR encoding and CID generation match upstream bytes/hashes. Decoding
  rejects noncanonical forms, invalid UTF-8, duplicate keys, floats, trailing data,
  and oversized/deep blocks. JSON request depth is bounded before parsing.
- Handle changes reserve targets against concurrent signup, reauthenticate after
  external validation, and append identity events atomically with local updates.
  PLC jobs preserve the latest directory fields and recheck custom-domain proof
  before submission. Tests cover remote acceptance followed by local rollback,
  stale leases after a later update, signing-key drift, lost custom-domain control,
  and reservation migration. A did:web account retains its original hostname;
  custom handles are resolved externally even when stored locally. Conflicting
  PLC jobs retain reservations and block deletion or another target until resolved;
  cancellation/admin reconciliation remains unfinished.
- PLC signing proofs use the transactional email outbox, expire after 30 minutes,
  and are bound to account, current address and purpose. Tests verify one-use
  consumption under concurrent requests, expiry, cross-account and app-password
  rejection, malformed fields, fresh directory defaults, removed signing authority,
  and reauthentication after remote lookup. Deactivated/taken-down accounts can use
  existing primary sessions; restricted taken-down login remains pending. Signing
  returns a verified successor without changing local or directory state. Full
  migration completion and external destination interoperability remain unverified.
- Signed submissions validate the current handle, repository key, server rotation
  key and PDS service before persistence. The existing identity queue serializes
  submissions and handle changes. Tests cover mismatched credentials, wrong signers,
  cross-account submission, pending conflicts, deletion blocking, revoked sessions,
  inactive owners, unconfirmed responses, local rollback after remote acceptance,
  and duplicate retries. Recommended rotation credentials follow the last confirmed
  operation, including removal of an old recovery key. Only canonical successors
  and already-confirmed operations are accepted; recovery forks need an explicit flow.
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
  proofs, not relay/client federation. CAR decoding verifies each block's content
  hash. The complete-repository verifier also accepts upstream-generated exports
  signed on both curves, including empty trees and trees after mixed mutations.
  It validates the expected DID/signature, version-3 commit fields, revision
  (no more than five minutes ahead), complete reachable records, paths, ordered
  MST traversal and canonical rebuilt root. It tolerates duplicate/out-of-order
  blocks and extra roots, and excludes unrelated blocks and arbitrary record
  links from ownership. Historical record objects are preserved without applying
  today's Lexicons. Current bounds are 64 MiB per CAR, 1,000,000 bytes per record,
  and 128 tree edges; legacy version-2 commits are unsupported.
  `importRepo` uses the saved source key for prepared destinations or the current
  local key for backups. It verifies uploads outside database locks, reauthenticates
  and detects concurrent changes before atomic replacement. Tests cover actual
  HTTP restoration, newer locally signed revisions, sync checkpoints, private
  migration imports, source signature checks, transaction rollback, app-password
  denial, revocation while parsing and concurrent import conflicts. A two-permit
  process-wide limit bounds buffered imports; excess requests return 503. Blob
  content reconciliation and final destination activation remain pending.
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
- Service token tests verify audience syntax, expiration boundaries, unique nonces,
  protected methods, case-insensitive privilege checks, account/session status and
  key isolation. Tokens carry `typ=JWT`, `kid=#atproto`, `iat`, `exp`, `iss`, `aud`,
  `jti`, and optional `lxm`. Pinned `@atproto/xrpc-server` 0.13.2 verifies both signing
  curves and tokens obtained from the HTTP endpoint, and rejects wrong audiences,
  methods and keys. The upstream library also generates tokens on both curves for
  our receiving verifier. Incoming checks bind type, method, audience, issuer and
  key algorithm; reject unsupported key IDs/critical headers; and recheck lifetime
  after network resolution. Only service JWT verification tolerates high-S ECDSA,
  matching the reference verifier; repository/PLC checks remain strict. PostgreSQL
  replay tests cover twelve concurrent uses, transaction rollback, reopened
  connections, issuer isolation, expiry cleanup and alternate signatures sharing
  one nonce. Destination account creation consumes verified proofs transactionally. This
  proves token interoperability, not external service access or complete migration.
- Destination preparation retains the verified source DID document, creates fresh
  encrypted keys and a private empty repository, and returns a deactivated primary
  session. PLC keys remain `prepared` until a source-authorized operation is accepted.
  Tests cover actual HTTP signup, invite/nonce/outbox rollback, concurrent creation,
  source key rotation during preparation, custom-handle proof, credential handoff,
  and public repo invisibility. Owner/admin activation is gated until final
  destination verification is implemented. Imported web DIDs resolve externally, including after
  local deletion; deleting a destination account removes its source snapshot/keys.
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
2. Bounded identity caching,
   signing/rotation key lifecycle, conflicted-operation administration, and migration.
3. Remaining administrative APIs, record/blob takedowns, and broader account recovery controls.
4. OAuth authorization server: metadata, PAR, PKCE, DPoP, client metadata/consent,
   refresh behavior, permission sets and scopes.
5. Relay notification and external relay interoperability,
   event retention/compaction, and remaining record/blob takedown semantics.
6. Completing destination activation, authenticated proxying to AppViews/labelers, and external
   client/relay end-to-end tests.
7. Streaming repository import, blob missing/list-since behavior, garbage collection,
   incremental MST mutation, streaming, quotas and bulk blob-backend migration.
8. PostgreSQL connection pooling, account-specific abuse controls, metrics/logging, CORS,
   operational deployment/TLS and backup/restore drills. Push CI is configured;
   its first GitHub execution still requires a push.

## Source pins

- Imported conformance fixtures: revision recorded in `test/fixtures/README.md`.
- Endpoint definitions consulted at upstream atproto revision
  `7a857989751ae31518509d69ab7194a922064f3d`.
- Protocol pages are moving specifications; see the roadmap's primary references.
