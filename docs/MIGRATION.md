# Account migration

Bluesky [private preferences and migration](PREFERENCES.md) are stored locally
through `app.bsky.actor.getPreferences` and `putPreferences`.

## Destination account preparation

Destination account preparation supports `createAccount` with an existing
`did:plc` or resolvable `did:web`. Authenticate with a service token from the source
PDS, addressed to this PDS and bound to `com.atproto.server.createAccount`. The
token issuer must exactly match `did`. Signup and invitation settings apply;
custom handles must prove their binding through DNS/HTTPS. The transaction consumes
the token and invite, reserves the account and handle, generates fresh encrypted
local keys, creates a private empty repository, and queues confirmation email.
`POST /xrpc/com.atproto.server.reserveSigningKey` may be called first, without
authentication, to learn the destination P-256 signing `did:key`; a reservation
for the account DID is consumed by preparation, expires after 24 hours, and at
most 4096 reservations are pending per database (overflow returns
`503 ReservationBusy`). Reservation private keys stay sealed with the master key
and unconsumed reservations are deleted during offline master-key rewrapping.
The returned primary session works while the account is deactivated. PLC accounts
can obtain `getRecommendedDidCredentials`, have the source sign those credentials,
and call `submitPlcOperation` here. Account preparation publishes no repository
events and does not alter the remote DID. A source identity snapshot is retained
for repository validation. After importing the repository and transferring DID
credentials, call `activateAccount` with the destination primary session. It
freshly verifies the remote signing key, PDS endpoint and handle; PLC identities
must also retain the destination's rotation key. Custom handles must still prove
their DNS/HTTPS binding. Activation rechecks authorization and local state after
network I/O, then publishes identity, active-account and sync events atomically.
Missing repository import returns `MigrationIncomplete`; mismatched credentials
return `IdentityMismatch`. Admin activation cannot bypass these checks. Blob
transfer can continue after activation; use `listMissingBlobs` to verify it.
This flow is tested against local HTTP/TLS fixtures, not an external reference PDS.

## Repository import

`POST /xrpc/com.atproto.repo.importRepo` accepts a complete version-3 CAR with
`Content-Type: application/vnd.ipld.car` and a primary access session. For prepared
destinations, it verifies the source signature against the retained DID document;
for local backup restoration, it uses the current local signing key. It validates
the complete MST and records before atomically replacing the record index,
retaining only reachable blocks, and signing a fresh destination commit. The new
revision exceeds both the imported and local revisions. Active accounts publish
a sync checkpoint; deactivated accounts remain private. Authorization is checked
again after parsing, and concurrent repository changes return `409 InvalidSwap`.
Imports stream hash-checked blocks into a private temporary file, with a 64 MiB
encoded-input limit and two simultaneous imports per process. Verification and
publication load blocks individually; CID/path metadata remains in memory.
Capacity exhaustion returns `503 RepoImportBusy`. See [import resource limits](REPO-IMPORT.md).
Blob bytes are transferred separately using `uploadBlob`, including through
inactive primary sessions. Imported historical record objects are preserved
without current Lexicon checks.

## Blob transfer verification

`GET /xrpc/com.atproto.repo.listMissingBlobs` lists referenced blob CIDs absent
from the authenticated account's blob metadata, with a representative `recordUri`.
It accepts `limit` (1–1000, default 500) and a CID cursor. Nested modern blob
objects and legacy `cid`/`mimeType` objects are indexed transactionally during
record writes and imports; ordinary CID links do not imply blob ownership.
Uploads to either PostgreSQL or S3 remove the matching CID from this list.
Inactive accounts' repositories and blobs remain hidden from public reads.
This endpoint checks database metadata; it does not probe S3 for lost objects.

Public `com.atproto.sync.listBlobs` lists the CIDs referenced by current records,
including references whose bytes have not arrived yet. Unreferenced uploads are
excluded. The optional `since` TID selects records changed after that repository
revision; the CID cursor and `limit` paginate the distinct result. Unchanged puts
keep their previous record revision. Imported records use the new destination
revision. Upgrading an older database assigns existing records the current repo
revision as a conservative baseline; it does not reconstruct historical revisions.

## Migration status

`GET /xrpc/com.atproto.server.checkAccountStatus` reports the authenticated
account's activation state, repository head/revision, owned block count, indexed
record count, distinct referenced blob count and stored blob count. Primary
sessions work while inactive; active app sessions may also read their own status.
`validDid` uses fresh remote resolution to check the signing key, PDS endpoint and
PLC rotation authority. Resolution failure returns `false` without hiding transfer
counts. It does not attest to handle binding or completed blob transfer, so use
`listMissingBlobs` as well. Counts include retained historical blocks and uploaded
unreferenced blobs; `privateStateValues` is currently zero. Responses are not cached.

## Service authentication

`GET /xrpc/com.atproto.server.getServiceAuth` issues a short-lived service JWT for
the authenticated account. Supply `aud` as a service DID or `did#serviceId` reference
(encode `#` as `%23` in the query), and preferably `lxm` as the exact target method.
Tokens use the repository signing key and a fresh random nonce. They expire after
60 seconds by default; an explicit `exp` may extend a method-bound token to at most
one hour. A method-less legacy token is limited to one minute. Protected account
methods cannot be authorized this way. Chat methods and `createAccount` require
a primary or privileged app-password session. Existing primary sessions for
taken-down accounts can request only `createAccount` for migration. Responses are
marked `Cache-Control: no-store`. These tokens cannot be used as local access or
refresh sessions. Issued tokens cannot be individually revoked before expiration.

Receiving-side verification and replay protection authenticate destination account
preparation. They require an exact method and PDS audience (the service DID or its
`#atproto_pds` reference), `typ=JWT`, and the issuer's current `#atproto` key resolved
through the bounded identity resolver. The maximum accepted lifetime is one hour,
with 30 seconds of allowance for a future issue timestamp. Expiration is rechecked
after resolution and before consuming the proof. PostgreSQL stores a hash of each
used nonce with its issuer, atomically with the protected mutation; rolled-back
mutations retain retryability. Cleanup removes at most 1,000 expired entries per
successful consumption. Authenticated [service proxying](PROXY.md) reuses this
policy. Other existing HTTP endpoints retain their current session authentication.
