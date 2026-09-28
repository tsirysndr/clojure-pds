# PDS compatibility checkpoint

Last verified: 2026-09-27. This is a development implementation, not a complete
AT Protocol PDS. Passing internal/fixture tests does not establish full network
interoperability. Run `bash scripts/test-redis.sh --with-s3` for the complete
Clojure suite and `node --test examples/email-worker/handler.test.mjs` for the Worker
contract. PostgreSQL tests use version 18.6 and the mise-pinned JDK 25.0.3.

## Implemented routes

All XRPC paths start with `/xrpc/`. Queries use GET (also HEAD); procedures use POST.

| Namespace | Methods | Scope |
| --- | --- | --- |
| `app.bsky.actor` | `getPreferences`, `putPreferences` | Private ordered preferences, inactive primary-session transfer, protected personal details, derived age flags, and RPC-scoped OAuth access; explicit alternate-AppView proxying |
| `com.atproto.server` | `describeServer` | Service DID, hosted suffix when signup is open, blob limit |
| `com.atproto.server` | `createAccount` | Configurable signup and service-authenticated destination preparation for existing web/PLC DIDs; inactive imports with new local keys; phone verification pending |
| `com.atproto.server` | `reserveSigningKey` | Public, bounded P-256 key reservations with sealed private material and 24-hour expiry; consumed by destination preparation for the same DID |
| `com.atproto.server` | `createInviteCode`, `createInviteCodes`, `getAccountInviteCodes` | Admin-issued codes, bounded batches, account listing and concurrent redemption limits; no automatic grants |
| `com.atproto.admin` | `getInviteCodes`, `disableInviteCodes`, `disableAccountInvites`, `enableAccountInvites` | Optional Basic admin credentials; full code listing with recent/usage keyset pagination, code invalidation and future-grant policy |
| `com.atproto.admin` | `getAccountInfo`, `getAccountInfos`, `searchAccounts`, `getSubjectStatus`, `updateSubjectStatus` | Private account inspection, including deduplicated batches that skip unknown DIDs and case-insensitive exact-email search with DID-keyset pagination; account, record and blob takedowns; independent activation state and signed repository data preserved; [moderation semantics](MODERATION.md) |
| `com.atproto.admin` | `updateAccountPassword`, `updateAccountEmail`, `updateAccountHandle`, `deleteAccount` | [Operator recovery/deletion](ADMIN-ACCOUNTS.md); session and proof invalidation, retained factors on recovery, transactional deletion and durable object cleanup; handle changes reuse the owner reservation/verification/PLC flow |
| `com.atproto.admin` | `sendEmail` | Bounded operator email to the account's registered address through the durable outbox; requires configured email delivery |
| `com.atproto.server` | `createSession`, `getSession`, `refreshSession`, `deleteSession` | Primary/app-password sessions, JWT type separation, single-use refresh, revocation |
| `com.atproto.server` | `getServiceAuth` | Repository-key JWTs, exact audience/service reference, method and expiration checks, primary/app-password privilege policy; shared proxy privilege policy |
| `com.atproto.server` | `createAppPassword`, `listAppPasswords`, `revokeAppPassword` | One-time secrets, scoped sessions, privileged flag, metadata-only listing, immediate revocation |
| `com.atproto.server` | `deactivateAccount`, `activateAccount` | Primary-session lifecycle; private inactive content; prepared destinations require repository import and freshly verified remote credentials; durable identity/account/sync publication |
| `com.atproto.server` | `checkAccountStatus` | Account-scoped activation, repository and blob counters; fresh DID credential validation, inactive primary sessions, no-store responses |
| `com.atproto.server` | `requestAccountDelete`, `deleteAccount` | One-use email token plus primary password; credential removal, tombstone and durable S3 cleanup |
| `com.atproto.server` | `requestEmailConfirmation`, `confirmEmail` | Durable email outbox, expiring one-use confirmation |
| `com.atproto.server` | `requestEmailUpdate`, `updateEmail` | Proof to current confirmed address, old-token invalidation, optional email authentication factor |
| `com.atproto.server` | `requestPasswordReset`, `resetPassword` | Same public result for known/unknown addresses; reset revokes sessions |
| `com.atproto.identity` | `resolveHandle`, `resolveDid`, `resolveIdentity`, `refreshIdentity` | Hosted identities and remote DNS/HTTPS handles, did:web/PLC documents, bidirectional checks; bounded public cache, explicit refresh, fresh internal security checks |
| `com.atproto.identity` | `updateHandle`, `getRecommendedDidCredentials` | Hosted/custom handles, durable audited PLC changes, stable web DID hostnames, public migration credentials; external migration conformance pending |
| `com.atproto.identity` | `requestPlcOperationSignature`, `signPlcOperation` | Primary session plus one-use emailed proof; verified latest audit, partial credential overrides; returns an operation without submitting it |
| `com.atproto.identity` | `submitPlcOperation` | Credential constraints, authorized successor signatures, durable directory reconciliation and atomic identity events; prepared destination identities supported; recovery forks use the separate operator CLI |
| `com.atproto.repo` | `createRecord`, `putRecord`, `deleteRecord`, `applyWrites` | Atomic signed commits; record/repo swap checks; batch maximum 200 |
| `com.atproto.repo` | `getRecord`, `listRecords`, `describeRepo` | Current records; keyset pagination and reverse order |
| `com.atproto.repo` | `importRepo` | Primary session, complete signed v3 CAR, atomic record replacement and destination re-signing; active sync checkpoint or private inactive import; disk-staged 64 MiB limit |
| `com.atproto.repo` | `uploadBlob` | Authenticated, maximum 5 MiB, account ownership; inactive primary sessions supported |
| `com.atproto.repo` | `listMissingBlobs` | Account-scoped referenced CIDs absent from blob metadata, distinct CID pagination and a representative record URI; inactive primary sessions supported |
| `com.atproto.sync` | `getRepo`, `getLatestCommit`, `getRepoStatus`, `listRepos`, `listReposByCollection` | Staged streaming full CAR export and local repository metadata; indexed active-repository enumeration by record collection; no incremental export optimization |
| `com.atproto.sync` | `getBlocks`, `getRecord` | Repository-owned historical blocks; signed MST inclusion/absence proofs; rootless block CARs |
| `com.atproto.sync` | `subscribeRepos` | Binary CBOR WebSocket stream; durable replay, cursor errors, bounded sends/backlog, account filtering; external relay integration pending |
| `com.atproto.sync` | `getBlob`, `listBlobs` | Binary round trip; distinct current record references, CID pagination and exclusive `since` revision filtering |
| `com.atproto.temp` | `checkSignupQueue` | No signup queue; authenticated accounts always report activated |

Additional routes: plain-text banner at `/`, liveness at `/xrpc/_health`, and hosted
identity documents at `/.well-known/did.json` and `/.well-known/atproto-did`.

Unknown XRPC routes also support [authenticated service proxying](PROXY.md) to
explicit DID service references or configured AppView/labeler defaults. Local
routes retain precedence. Real HTTP/TLS fixtures verify authentication, scoped
upstream JWT signatures, raw forwarding, per-account proxy budgets, network
boundaries and failure handling.
OAuth discovery, browser authorization and DPoP resources are mounted; see
[the OAuth coverage and limits](OAUTH.md). External service interoperability remains pending.

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
  deleted and service identities. Public resolution uses a bounded, configurable
  [identity cache](IDENTITY-CACHE.md); `refreshIdentity` fetches fresh data and
  fences older in-flight lookups. Internal security-sensitive resolvers remain
  uncached. Remote PLC lookups, including for hosted accounts, verify the signed
  audit log and derive its canonical DID document before caching. Tests cover
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
  the explicit [operator reconciliation CLI](PLC-RECONCILIATION.md) adopts compatible
  verified external history, including recovery forks and descendants retaining
  queued keys. It requires expected local/queued/remote CIDs and proves that a
  delayed queued submission cannot replace the observed history before deleting
  a job. Tests cover concurrent operators, stale workers, transactional rollback,
  key corruption, handle collision/proof, CID/material races and repeat receipts.
  Signing adoption preserves records and emits identity/sync checkpoints; canceled
  signing jobs also restore an active account's checkpoint. The pinned PLC library
  independently verifies delayed-operation authority. Recovery-fork submission
  and public-directory deployment remain unverified.
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
  and already-confirmed operations are accepted by XRPC; recovery forks use the
  separate [explicit operator flow](PLC-RECOVERY.md).
- Record schema validation uses 17 record roots in the pinned, checksummed catalog
  and [authenticated dynamic schemas](LEXICON-RESOLUTION.md#dynamic-record-writes)
  for explicitly validated writes outside that catalog.
  Tests cover upstream record fixtures, required/nullable fields, nested unions,
  references, UTF-8/grapheme limits, blobs, string formats, and key rules. Unknown
  schemas remain writable by default; explicit validation resolves a complete,
  bounded schema graph or fails. Default validation also uses unexpired cached
  graphs. Resolution runs outside transactions, with authorization rechecked before
  mutation. Tests cover signed cross-namespace dependencies, malformed proofs,
  cache bounds/expiry, revocation and concurrent put-action changes.
  Invalid batches roll back records, blocks and commits. CID string formats are
  restricted to the blessed AT Protocol CID set; see fixture notes.
- The catalog now contains 108 schemas covering the record roots and all 71
  implemented XRPC endpoints and their references. JSON inputs and typed query
  parameters enforce required/nullable fields, formats, nested references/unions,
  and scalar/array constraints at the existing request-reading boundaries.
  Endpoint defaults apply without changing unknown record content. Repeated
  parameters require a declared array; extension fields remain allowed. Startup
  rejects implemented `com.atproto` routes without a matching schema/method.
  Generated input cases are checked against pinned `@atproto/lexicon` 0.7.14;
  HTTP tests verify auth ordering, invalid optional fields, nullable compare-and-swap
  and that rejected requests leave accounts, sessions, records, blocks, events
  and email unchanged. Binary inputs keep the existing bounded readers.
- The integration runner observes actual successful PDS responses and outgoing
  WebSocket frames without changing production handlers. Local schema checks and
  the pinned upstream validator check JSON bodies, media types, empty procedure
  responses, frame envelopes, native bytes/CID links, all five message variants
  and error payloads. Required coverage includes all 71 implemented endpoints and
  `#commit`, `#sync`, `#identity`, `#account`, `#info`, plus error frames. Negative
  checker tests ensure missing fields, null optional values, wrong content types
  and malformed envelopes fail. HTTP/WebSocket tests additionally connect a
  reactivation sync checkpoint to its repository head/revision and verify session
  revocation. Test fixtures stay local and temporary oracle files are deleted.
  This is schema coverage of produced samples; it does not establish external
  relay/client interoperability or exhaust all account states and value combinations.
- P-256 and secp256k1 signatures enforce the 64-byte low-S format using Bouncy Castle;
  verification runs upstream signature vectors. Repositories currently sign with P-256.
- MST height/prefix vectors and before/after commit root fixtures match upstream.
  `bash scripts/test-conformance.sh` additionally uses pinned `@atproto/repo`
  0.10.14 to verify a signed export, inclusion/absence proofs, false-claim rejection,
  and rootless CARs obtained over HTTP. This covers repository serialization and
  proofs, not relay/client federation. CAR decoding has a bounded InputStream
  visitor with hash checks before callbacks, strict framing/EOF handling, duplicate
  byte accounting and caller-owned input lifetime. The byte-array decoder uses
  this reader while retaining its existing buffered return shape. CAR decoding verifies each block's content
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
  process-wide limit bounds staged imports; excess requests return 503.
  [Disk-staged imports](REPO-IMPORT.md) read at most 64 KiB from the upload at once,
  retain CID/offset/path metadata, and load payloads individually for hash/signature,
  canonical-tree and record checks. The destination signs the verified root without
  collecting/rebuilding its encoded nodes. Tests reject buffered import helpers,
  restore multi-megabyte content, exercise chunked HTTP and disconnect cleanup,
  and inject a disk-read failure after publication begins to prove full rollback.
  A one-connection pool remains available during reads; legacy/OAuth revocation
  after staging still denies publication. Metadata and canonical rebuilding remain
  O(n); large-scale throughput and heap benchmarks are pending.
- An experimental SQLite backend is selected when no PostgreSQL connection is
  configured (or by a `jdbc:sqlite:` URL). A cached dialect translator covers
  the codebase's PostgreSQL construct set and fails fast on anything else;
  immediate write transactions replace row/advisory locking; declared-type
  read coercion preserves Java types; and the consolidated baseline schema
  reproduces checks, keys, partial indexes and the OAuth-epoch trigger. A
  dedicated end-to-end slice plus 66 integration namespaces (295 tests) run
  against temporary SQLite files in every suite execution, covering accounts,
  OAuth, repositories, blobs, the firehose, moderation, PLC operations, the
  service proxy and the operator recovery CLI. Migration-history replays,
  row-lock choreography, `ALTER CONSTRAINT` fault injection, pool
  connection-identity tests, the master-key CLI and the backup drill remain
  PostgreSQL-only. See [scope](SQLITE.md).
- PostgreSQL migrations are locked, transactional, and checksummed. Tests verify
  rollback, persistence across connections, binary data, signed commits, atomic
  batches, concurrent swap conflicts, and isolation between accounts.
  A transactional ownership migration traverses retained commit/MST history;
  it does not grant ownership from arbitrary record links. Public block requests
  cannot retrieve blocks belonging only to a different account.
- The server owns a [configurable HikariCP connection pool](DATABASE.md), shared
  by HTTP and background work. Real PostgreSQL tests verify bounded acquisition,
  rollback and JDBC state reset on reuse, concurrent transactions, replacement
  of terminated connections, sanitized overload responses, startup failure
  cleanup and pooled account/record HTTP requests. Process tests cover startup
  and shutdown. Deployment load/failover testing remains pending.
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
- Owned HTTP stream bodies use one 64 KiB buffer and wait for each write callback
  (30-second deadline). Real socket tests cover multiple chunks, empty bodies,
  HEAD/204/304 suppression, declared-length mismatches, read failures, slow-client
  backpressure, disconnect and shutdown cleanup. Blob downloads adopt this
  contract, as do full/partial repository exports and successful service-proxy
  responses. Proxy responses are completely staged under their configured limit
  before publication; 64 KiB copy buffers, held delivery permits and file cleanup
  bound payload memory/disk. POST request bodies stage into their own private
  file before any remote work and are sent once with a known length; oversize
  bodies fail with 413 before contacting the upstream. Local TLS and
  socket tests cover limits, truncation, partial-body timeout, no retries,
  connection release, HEAD/204, sanitized errors, downstream disconnects,
  chunked request uploads and request-file cleanup.
- `getRepo` walks the current stored commit/MST graph and emits one hash-checked
  block at a time into an owned temporary file. It does not rebuild the MST or
  collect record payloads into a whole-repository map. A transaction-local
  PostgreSQL table deduplicates shared CIDs and drops on commit/rollback. Account
  and repository locks protect preparation; all database resources are released
  before HTTP delivery. Export tests compare complete block sets, preserve
  signed takedown records, exclude history/arbitrary record links, verify
  concurrent snapshot consistency, and cover limit/corruption/capacity cleanup.
  Existing socket and pinned upstream sync tests verify the resulting CARs.
  `PDS_REPO_EXPORT_MAX_BYTES` defaults to 256 MiB (1 MiB–16 GiB); two process-wide
  staging/delivery slots bound temporary CAR payload. See [export limits](REPO-EXPORT.md).
- `getBlocks` and `getRecord` also stage streamed CAR bodies, sharing the two
  export slots. Selected blocks load individually; record proofs visit the MST
  path without collecting its payloads. Hash/ownership checks precede response
  publication, duplicate CIDs count once, and the 63 MiB block-payload limit
  remains separate from the configurable full-export limit. Socket/upstream tests
  cover inclusion/absence, history ownership, HEAD cleanup and output validity;
  focused tests reject use of the buffered CAR/proof builders on these paths.
- Blob downloads verify size and CID incrementally before responding. PostgreSQL
  returns bytea slices of at most 64 KiB, and S3 reads at most the recorded size
  plus one byte. A private temporary file avoids holding the whole blob in the
  JVM heap and releases backend resources before HTTP delivery. Sixteen permits
  bound staging plus delivery to 80 MiB of temporary payload per process; overflow
  returns `503 BlobDownloadBusy`. PostgreSQL/S3 tests cover 5 MiB downloads,
  corruption, empty blobs, missing objects, capacity release and disconnects.
  Internal byte-array reads and remote migration downloads remain buffered.
- Public blob uploads stage and hash client bytes in 64 KiB chunks without holding
  database connections/account locks. Fixed-length and chunked HTTP bodies share
  the zero-through-5-MiB limit. Publication rechecks session/OAuth authorization,
  then uses known-length PostgreSQL `setBinaryStream` or an S3 content provider
  with independent readers for signing/retry. Sixteen separate upload permits cap
  temporary upload payload at 80 MiB; overload returns `503 BlobUploadBusy`.
  Tests cover maximum-size persistence, real chunked requests and disconnects,
  incomplete/oversized input, capacity and file cleanup, revocation during reads,
  DPoP replay rejection, and byte-identical SDK retries. Publication still holds
  the account/blob locks while persisting to the backend, preserving duplicate,
  takedown and uncertain-commit behavior.
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
  session. `reserveSigningKey` reservations are public, capped at 4096 pending
  rows, expire after 24 hours, repeat per DID, seal private keys with the master
  key and are consumed transactionally by preparation for the same DID; master-key
  rotation deletes unconsumed reservations. HTTP tests cover validation, repeats,
  capacity errors, consumption into the prepared repository key and fresh-key
  fallback. PLC keys remain `prepared` until a source-authorized operation is accepted.
  Tests cover actual HTTP signup, invite/nonce/outbox rollback, concurrent creation,
  source key rotation during preparation, custom-handle proof, credential handoff,
  and public repo invisibility. Admin activation cannot bypass destination
  verification. Imported web DIDs resolve externally, including after
  local deletion; deleting a destination account removes its source snapshot/keys.
- Destination activation requires imported repository state and fresh matching
  signing key, handle and PDS service credentials; PLC audits must include the
  retained server rotation key. External custom-handle proof is rechecked.
  Activation reconciles externally submitted PLC credentials without directory
  writes, and atomically removes the import gate and publishes identity/account/sync
  events. Tests cover HTTP activation, wrong credentials, missing rotation authority,
  rollback, revoked sessions, concurrent imports and duplicate activation. Blob
  completeness does not gate activation. External reference PDS migration testing
  remains pending; current tests use local HTTP/TLS fixtures.
- Migration status tests follow a prepared account through signed repository
  import, blob upload, credential transfer, activation and deactivation over HTTP.
  Counters are account scoped: owned blocks (including history), indexed records,
  distinct expected blob CIDs and uploaded blob metadata (including unreferenced
  blobs), plus stored private preference entries. Derived age flags are not counted.
  [Private preference tests](PREFERENCES.md) cover inactive transfer, hidden personal
  details, replacement, namespace isolation, RPC permissions, and deletion. `validDid` checks
  fresh remote signing key/PDS credentials and PLC rotation authority, independently
  of handle binding, account activation and content completeness. Lookup failures
  return false; authorization is checked again after network I/O, and credential
  changes invalidate the earlier verdict. Tests cover active app-password reads,
  cross-account query parameters, inactive restrictions and revoked sessions.
- Blob bytes can use PostgreSQL or configurable S3-compatible storage. An upgrade
  test preserves existing blobs. Moto-backed HTTP tests cover binary round trips,
  ownership, duplicate uploads, missing objects and failed PUT rollback; fault
  tests cover corrupted content. Live provider behavior remains unverified.
- Record blob references are indexed in the same transaction as normal record
  writes and CAR import; deletion/replacement removes obsolete references.
  Migration backfill preserves existing record bytes and repository heads.
  Modern nested and legacy-shaped blob references are indexed; malformed historical
  references and ordinary CID links/strings are ignored. Missing-blob queries use
  per-account metadata, group by CID and paginate in bytewise order. PostgreSQL
  and real Moto/S3 tests transfer blobs into inactive accounts, verify missing-list
  reduction and duplicate retries, preserve private visibility and test rollback.
  Missing-list queries do not inspect external objects; a lost S3 object is detected
  by the integrity-checked read path and still requires operational repair.
- Public blob synchronization lists current record references, including missing
  content, rather than all uploaded metadata. Per-record revisions are updated
  atomically with changed records and imports; no-op puts retain their revision.
  Tests cover shared references, pagination, exclusive `since` bounds, record
  deletion, failed-write rollback, destination import revisions and metadata-free
  references. Migration assigns old records their current repository revision as
  a conservative baseline without rewriting signed content; earlier per-record
  history cannot be recovered from that metadata alone.
- Temporary uploads are hidden from public download until referenced. Final-reference
  removal and full repository replacement remove unused blob metadata atomically;
  batched record moves preserve shared content. Expired temporary uploads use a
  configurable 1-hour–30-day grace period (24-hour default), with 50-blob sweeps.
  PostgreSQL bytes disappear transactionally; S3 uses durable retry jobs. Workers
  use shared blob locks without waiting on lock-order inversions and preserve
  locators still in use. New S3 PUTs use unique generation keys to isolate late
  remote DELETEs after timeouts. Tests cover rollback, renewal, batch bounds,
  overlapping upload/deletion, real Moto removal and delayed deletion isolation.
  Downloads include a sandbox CSP and explicit content length. Failed-commit S3
  orphans and physical historical-block reclamation remain pending.
- Zero-byte blobs follow the same upload/reference/download/deletion lifecycle on
  PostgreSQL and S3, with `Content-Length: 0` on download. Negative sizes and
  mismatched metadata remain invalid, and application Lexicon size/MIME constraints
  still apply. Migration 023 reindexes earlier imported empty references without
  altering record bytes or repository heads/revisions. This follows the
  [blob specification](https://atproto.com/specs/blob), which explicitly permits
  empty content; HTTP round trips, bounded reads and upgrade preservation are tested.
- A child-JVM test runs `pds.main`, checks HTTP health, and verifies graceful shutdown,
  including optional S3/Redis clients when the combined suite enables them.
- An opt-in [PostgreSQL recovery drill](BACKUP.md), enabled in CI, uses the shipped
  backup/restore CLI against fresh databases. It compares all tables and verifies
  restored login/session behavior, private preferences, moderation flags, blobs,
  signed writes, sequence advancement and real WebSocket replay with the upstream
  verifier. It also tests corrupt archives and refusal to overwrite existing data.
  S3 coverage verifies retained object locators, not provider disaster recovery.
- Rate limiting defaults to bounded in-memory counters. Optional Redis uses atomic
  expiring counters; real Redis 8.2.3 tests cover concurrent budgets shared by two
  clients, expiry, reopen, isolation and failures. Forwarding headers remain ignored.

## Remaining work for a full PDS

1. Expanded behavioral conformance/catalog coverage as endpoints and protocol
   features are added, including deployed Lexicon publisher interoperability.
2. Deployed recovery, migration and key-rotation conformance.
   [Managed control/signing-key rotation](KEY-ROTATION.md)
   has an operator CLI, durable retries and public completion receipts. Signing-key
   rotation preserves the MST root and records, pauses signed work during pending
   PLC publication and emits identity/sync checkpoints after confirmation. Local
   TLS/PostgreSQL/HTTP/WebSocket tests and upstream CAR verification cover these paths;
   public-directory and relay interoperability remain unverified.
   [Account-held recovery-key management](PLC-RECOVERY-KEYS.md) has an operator CLI
   and email-authorized browser controls for ordered replacement/removal, plus
   existing owner XRPC flows. Browser changes bind the DID to the recent owner
   session, recheck authorization after directory I/O, and consume email proof
   atomically with queue insertion. Reloads can retry the saved intent without
   another code. HTTP and race tests cover these boundaries; Chrome desktop/mobile
   preview checks cover display and retry interactions.
   Tests cover bounded key lists, no-op behavior, immutable retries, receipts,
   account/repository preservation, concurrency, rollback, stale workers and
   conflict reconciliation. The pinned PLC library verifies key ordering and the
   removed higher-priority key's remaining recovery authority.
   [Externally signed recovery submission](PLC-RECOVERY.md) checks reviewed head
   and operation CIDs, recovery priority/window and full audit confirmation. It
   supports tombstones and both curves, and never reposts accepted/nullified CIDs.
   TLS, PostgreSQL and pinned-reference tests cover ambiguous responses, branch
   races and subsequent fenced local adoption. The directory POST has no atomic
   head comparison; this limitation is explicit in the procedure.
   [Offline master-key rewrapping](MASTER-KEY.md) covers repository/PLC keys, pending
   replacements and TOTP secrets with atomic rollback and startup key checks.
   Legacy sessions/app passwords are invalidated; OAuth access/refresh and queued
   PLC operations survive. Deployment secret-store cutover remains operator-managed.
3. Deployment-specific owner-verification policies. Administrative account
   inspection/search, operator email, invite listing and handle updates are
   implemented; entryway-managed signing-key updates and phone verification do
   not apply to this deployment model.
   [Local all-authenticator-loss recovery](ADMIN-ACCOUNTS.md#recovery-after-losing-every-authenticator)
   is implemented with password replacement, factor removal, session revocation,
   security-version checks and retry-safe audit receipts.
4. OAuth authorization server: deployed reference-client interoperability.
   Deactivated migration accounts authorize and use epoch-scoped OAuth sessions
   on inactive-capable endpoints; status changes end sessions from the previous
   period. Account-status permission semantics are not yet defined in the
   published permission specification.
   [DPoP replay protection, metadata/JWKS, confidential clients, PAR/PKCE, browser
   signup/consent, token rotation/revocation, owner session management and transitional
   resource authorization](OAUTH.md) are implemented and mounted with discovery.
   Direct collection/action, blob MIME, RPC audience/method, email/repository
   management and identity scopes are enforced and shown in consent. An upstream
   client flow covers granular grants; local TLS fixtures verify PLC management.
   Permission sets use authenticated resolution, shared caching, namespace checks,
   immutable access-token permissions and expandable localized consent. Another
   upstream-client flow covers set-authorized signup, writes, refresh and revocation.
   Pinned upstream Node client tests verify public signup and confidential login,
   writes, refresh and revocation against a local HTTP fixture. Bounded grant
   cleanup preserves replay evidence until absolute session expiry.
   Optional [TOTP and passkeys](ACCOUNT-SECURITY.md) include browser enrollment,
   removal and OAuth login integration; hardware/browser ceremony verification
   and deployment-specific recovery verification remain pending.
5. External relay interoperability and event retention/compaction.
   [Record/blob takedowns](MODERATION.md) now implement local indexed-read/blob
   access controls; they do not erase signed sync data or remote copies.
   [Opt-in relay announcements](RELAY.md) are
   implemented with durable schedules, bounded HTTPS, retries and fenced leases;
   local tests do not prove that a deployed relay accepts or consumes this PDS.
6. Streaming proxy transfers and external reference-PDS migration and
   AppView/labeler/client/relay end-to-end tests. Bluesky private preferences can
   now be exported/imported locally; other applications and external services may
   define separate state-transfer mechanisms.
7. Streaming repository import, S3 orphan and historical-block reclamation,
   incremental MST mutation, streaming, quotas and bulk blob-backend migration.
8. Metrics/logging, database load/failover testing,
   operational deployment/TLS, production PITR and external S3 recovery drills.
   Local logical database backup/restore is tested. Push CI is configured;
   its first GitHub execution still requires a push.

## Source pins

- Imported conformance fixtures: revision recorded in `test/fixtures/README.md`.
- Endpoint definitions consulted at upstream atproto revision
  `7a857989751ae31518509d69ab7194a922064f3d`.
- Protocol pages are moving specifications; see the roadmap's primary references.
