# Managed identity-key rotation

The local operator CLI rotates the PDS-managed `did:plc` control key. It generates
a new secp256k1 key, replaces the old key at the same position in `rotationKeys`,
and preserves the other keys and their priority, aliases, services and repository
verification methods. A separate command rotates the P-256 repository signing key
for managed PLC and hosted web identities, creating a new signed repository head.
User recovery-key replacement and recovery forks remain separate lifecycle work.
[Offline master-key rewrapping](MASTER-KEY.md) changes encryption at rest without
changing these public signing/control keys.

## Operator commands

Upgrade every PDS instance and worker to a version supporting migration 039 before
queuing a rotation. Older workers cannot install replacement key material. Use the
same database and public-origin configuration as the serving PDS. Database access
and the master key authorize this local CLI; it is not a public XRPC endpoint and
does not accept a user session in place of operator access.

Inspect the locally confirmed operation CID, public control/signing keys and
repository head (`status` also supports hosted `did:web` accounts):

```sh
mise exec -- clojure -M:identity status "$DID"
```

To rotate a managed PLC control key, supply that CID explicitly:

```sh
mise exec -- clojure -M:identity rotate-plc-key "$DID" "$EXPECTED_OPERATION_CID"
```

To rotate the repository signing key, supply the `signingKey` from `status`:

```sh
mise exec -- clojure -M:identity rotate-signing-key "$DID" "$EXPECTED_SIGNING_DID_KEY"
```

The expected value is the current public P-256 `did:key`, not a private key.
Hosted `did:web` rotation is one local transaction; externally managed/imported
web DID documents are unsupported. PLC rotation updates `verificationMethods.atproto`
through the durable queue while retaining control/recovery keys, aliases and services.

`status` needs database configuration only; rotation also requires
`PDS_MASTER_KEY`, the correct `PDS_PUBLIC_URL` and the normal PDS configuration.
The command applies missing database migrations. It opens a bounded database pool
and guarded HTTPS client, then closes them before exit. It uses the account's
persisted PLC directory URL, so changing the server's signup directory does not
redirect an existing identity's rotation.

Output is JSON containing public keys, CIDs and state. It never exports private
keys or sealed key bytes. Exit codes are 0 for ready/completed state, 2 for pending
or working state, 1 for failed operations, and 64 for invalid command syntax.
`--help` prints usage without opening dependencies. Unexpected errors are
sanitized; inspect `status` after an interrupted or failed command.

## PLC control-key operation and retries

The expected CID must match both the locally confirmed operation and a freshly
verified canonical directory head. The audit must still authorize the current
control key, repository verification key, public PDS endpoint and current handle.
An externally changed identity is not automatically overwritten or rebased.

Preparation performs remote I/O without a database transaction. The account and
identity are checked again under the account lock before saving the signed
operation, operation CID and encrypted replacement private key in `handle_updates`.
The key uses the existing master-key sealing context bound to the account DID.
Concurrent requests for the same expected CID converge on one queued operation.

The normal identity worker processes this queue. It checks that the stored private
key matches the replacement public key before any submission. It confirms the
exact operation as the canonical directory head, then atomically installs the key,
updates the local operation, writes a public completion receipt and emits one
identity event. The old private key is replaced in live storage. Historical backups
and database WAL can still contain it; retention and access policy remain an
operator responsibility.

Retry the exact command with the original expected CID. A queued operation reuses
its signed bytes and replacement key. A completed operation returns its public
receipt, even after a later handle update or rotation. The receipt means that
operation was installed at that time; use `status` to inspect the current key.
Retrying does not rotate again. A new rotation requires the new confirmed CID.

Unconfirmed directory responses back off through the existing queue. An explicit
CLI retry makes pending/failed work due immediately, but cannot steal a live
worker lease. Lost responses after directory acceptance are reconciled by audit.
If local finalization fails after acceptance, the lease expires and a worker
reconciles the same operation. Stale workers cannot install keys or emit another
event after a newer worker finishes.

## Control-key availability and conflicts

Rotation is supported for established active, deactivated and taken-down managed
PLC accounts. It preserves account status, sessions, repository signatures and
records. Provisioning and prepared migration destinations must finish first.
Owner handle changes, PLC signing/submission, deletion and other rotations cannot
replace a pending rotation. Ordinary repository work can continue because its
signing key and published verification method are unchanged.

A conflicting directory head or invalid audit fails closed. The queued operation
and encrypted replacement key remain available for diagnosis. The explicit
[PLC reconciliation procedure](PLC-RECONCILIATION.md) can adopt compatible verified
history and resolve safely superseded jobs. Do not delete queue
rows after an ambiguous submission: the directory may already have accepted the
replacement key. Recovery-fork submission remains a separate unimplemented flow.

The replacement preserves priority according to the
[PLC key rotation and recovery rules](https://web.plc.directory/spec/v0.1/did-plc).
It is not a substitute for recovery from a compromised higher-priority key. Identity
notifications follow the [sync identity-event semantics](https://atproto.com/specs/sync).
Public cached DID documents are unaffected by changing only a PLC control key,
which is not part of the DID document.

## Repository signing-key operation and retries

Hosted web rotation atomically replaces the encrypted private/public key, creates
an increasing repository revision over the existing MST root, writes a public
receipt and emits identity followed by sync events. Record bytes, record revisions,
moderation flags, blob references and account sessions are preserved. The hosted DID
document reads the replacement public key from the same committed database state.

PLC rotation first verifies a fresh directory audit against the confirmed local
identity, then rechecks identity/key state under the account lock before storing the
signed operation and encrypted replacement key. Writes may finish during remote
preparation; the final commit signs the latest committed data root. Once queued,
record writes, repository imports, signed sync reads (`getRepo`, `getLatestCommit`,
`getRecord`, `getBlocks`) and newly issued service tokens return HTTP 503
`SigningKeyRotationPending`. `listRepos` omits that repository, and the firehose
suppresses commit/sync frames while leaving account/identity events available.
These restrictions also apply to failed jobs because directory acceptance may be
ambiguous. Ordinary record/blob reads and existing session inspection remain available.

After the directory confirms the exact operation, the worker atomically installs
the key, re-signs the current repository root, saves a public receipt and emits
identity followed by sync. The completion sync allows consumers to resynchronize
if their stream cursor advanced while commit events were suppressed. Deactivated
and taken-down accounts retain their status and emit only identity; reactivation
uses the existing sync checkpoint path. Repository signatures and checkpoints
follow the [repository signing-key rotation rules](https://atproto.com/specs/repository)
and [sync specification](https://atproto.com/specs/sync).

Retry with the original expected signing public key. Pending work reuses the exact
stored key and operation, while completed work returns the original public receipt
containing `signingKey`, `repoCommit`, `repoRev` and, for PLC, `operationCid`. Repeated
commands do not create additional commits or events. Use `status` for current
state: receipts describe the completed rotation even after later writes/rotations.
A new rotation requires the new current signing key.

Both rotation types use the same queue, leases and confirmation rules. Competing
handle changes, PLC submissions, other rotations and deletion cannot replace a
pending job. A local rollback after directory acceptance retains the encrypted
replacement for the next worker; stale workers cannot install it twice. There is
no automatic cancellation or adoption of conflicting directory changes. Use the
explicit [operator reconciliation procedure](PLC-RECONCILIATION.md) when appropriate.
Provisioning and prepared migration destinations must finish first.

Historical commits and already issued service JWTs retain their original
signatures; consumers resolving only the current DID key may reject them. The
identity event requests a refresh, and the new checkpoint/export verifies with
the replacement key. Previously cached DID documents can remain stale until
refreshed or expired. Remote publication and local finalization cannot form one
distributed transaction; the pending guard prevents issuing fresh old-key data
during that interval. Old private keys are replaced in live storage, but backups
and WAL can retain them. Receipts contain only public material.

## Verification

Real TLS directory/PostgreSQL tests cover priority and metadata preservation,
continued repository identity, subsequent handle signing with the new key,
concurrent requests, retries, rejected and ambiguous submissions, local rollback,
stale leases, corrupted sealed material and conflicting remote operations. A
separate JVM invokes the shipped CLI for status without a master key. The pinned
`@did-plc/lib` verifier checks the persisted rotation and subsequent signed update.
Signing-key tests additionally cover real HTTP exports and service JWTs, real
WebSocket identity/sync delivery, unchanged records and moderation flags, waiting
writers, inactive status, corrupt replacement material, remote conflicts and
rollback after acceptance. The pinned `@atproto/repo` verifier checks old/new CAR
signatures, unchanged MST roots, advancing revisions and the emitted checkpoint.
These tests do not submit operations to the public PLC directory; deployed key
rotation and recovery remain unverified.
