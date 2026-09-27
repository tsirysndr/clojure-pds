# Managed PLC control-key rotation

The local operator CLI rotates the PDS-managed `did:plc` control key. It generates
a new secp256k1 key, replaces the old key at the same position in `rotationKeys`,
and preserves the other keys and their priority, aliases, services and repository
verification methods. PLC control keys and repository signing keys serve distinct
roles; this command implements the control-key lifecycle. Repository signing-key
rotation, user recovery-key replacement, recovery forks and master-key rewrapping
remain separate work.

## Operator commands

Upgrade every PDS instance and worker to a version supporting migration 038 before
queuing a rotation. Older workers cannot install replacement key material. Use the
same database and public-origin configuration as the serving PDS. Database access
and the master key authorize this local CLI; it is not a public XRPC endpoint and
does not accept a user session in place of operator access.

Inspect the locally confirmed operation CID and public control key:

```sh
mise exec -- clojure -M:identity status "$DID"
```

Then supply that CID explicitly:

```sh
mise exec -- clojure -M:identity rotate-plc-key "$DID" "$EXPECTED_OPERATION_CID"
```

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

## Durable operation and retries

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

## Availability and conflicts

Rotation is supported for established active, deactivated and taken-down managed
PLC accounts. It preserves account status, sessions, repository signatures and
records. Provisioning and prepared migration destinations must finish first.
Owner handle changes, PLC signing/submission, deletion and other rotations cannot
replace a pending rotation. Ordinary repository work can continue because its
signing key and published verification method are unchanged.

A conflicting directory head or invalid audit fails closed. The queued operation
and encrypted replacement key remain available for diagnosis; there is no automatic
cancellation, recovery fork or conflict-adoption command yet. Do not delete queue
rows after an ambiguous submission: the directory may already have accepted the
replacement key. Operator conflict reconciliation remains on the roadmap.

The replacement preserves priority according to the
[PLC key rotation and recovery rules](https://web.plc.directory/spec/v0.1/did-plc).
It is not a substitute for recovery from a compromised higher-priority key. Identity
notifications follow the [sync identity-event semantics](https://atproto.com/specs/sync).
Public cached DID documents are unaffected by changing only a PLC control key,
which is not part of the DID document.

## Verification

Real TLS directory/PostgreSQL tests cover priority and metadata preservation,
continued repository identity, subsequent handle signing with the new key,
concurrent requests, retries, rejected and ambiguous submissions, local rollback,
stale leases, corrupted sealed material and conflicting remote operations. A
separate JVM invokes the shipped CLI for status without a master key. The pinned
`@did-plc/lib` verifier checks the persisted rotation and subsequent signed update.
These tests do not submit operations to the public PLC directory; deployed key
rotation and recovery remain unverified.
