# Reconcile an externally changed PLC identity

The local operator CLI can adopt verified directory history when another client
changed the identity or a queued operation was accepted and then superseded by a
later operation. Authority comes from access to the database and master key.
This procedure sends no PLC operations and cannot recover private keys the PDS
does not hold.

Inspect the established managed PLC account using the server's normal environment:

```sh
mise exec -- clojure -M:identity inspect-plc DID
```

Inspection needs database access and the configured public URL/user domain, but
does not need the master key. It fetches the full verified audit from the account's
stored directory URL. Its public JSON includes `localCid`, `queuedCid`, `remoteCid`,
`directoryData`, and `queuedDisposition`. `queuedCid` is `-` when no job exists.
A `plan` identifies the adopted handle and whether each key comes from `current`
or `queued` material; otherwise `blockedBy` explains an incompatible state.
Inspection does not decrypt keys or prove an external handle, so a plan is subject
to those checks during reconciliation. It is an observation, not a reservation.

Review the directory's handle, endpoint and keys. Supply exactly the observed CIDs:

```sh
mise exec -- clojure -M:identity reconcile-plc DID LOCAL_CID QUEUED_CID_OR_- REMOTE_CID
```

The mutation requires `PDS_MASTER_KEY` and holds the same master-key maintenance
lease as the server. It verifies the audit again, checks the required encrypted
private keys against their public keys, proves a custom handle through DNS/HTTPS,
and refetches the directory head before entering the local adoption transaction.
The account lock then protects another comparison of local identity, queued
operation, handle and key material. Changed CIDs or material produce
`IdentityMismatch`; inspect again before issuing a new command.

The transaction reserves the adopted handle, installs any queued key still used
by the directory, updates the local PLC snapshot, removes the queue row, releases
obsolete handle reservations, and emits an identity event. Replacing a signing key
also signs the current MST root with a new revision and emits an active account's
sync checkpoint. Canceling a superseded signing job retains the current key and
repository commit but still emits a sync checkpoint: clients may have skipped
commit events while the signing guard was active. Inactive and taken-down accounts
keep their status and emit no sync checkpoint. Sessions, records, blob references,
private preferences and moderation flags are preserved.

Public `plc_reconciliations` receipts record the expected CIDs, adopted key sources,
handle, queue disposition, database role and completion time. Repeating the exact
command returns its original result without undoing later identity changes or
emitting more events. A rotation installed during reconciliation also gets its
normal rotation receipt; its `operationCid` is the directory head that confirmed
the adopted key, which can differ from the originally queued operation CID.
Receipts describe completed actions, not necessarily current identity state.

## Why a queue row cannot simply be deleted

A timed-out or failed directory submission may still be accepted. A delayed
operation can even replace a competing branch if its signer has higher priority
under the [PLC recovery rules](https://web.plc.directory/spec/v0.1/did-plc).
Reconciliation verifies signatures and recomputes the audit's canonical history
and nullification flags before classifying the queued operation:

- `accepted`: the exact queued operation is in canonical history, including when
  later operations extend it.
- `superseded`: the exact operation was nullified, its parent was nullified, or a
  competing canonical child of its parent was signed by a key of equal or higher
  priority. The delayed queued operation cannot replace that history.
- `unresolved`: it could still be accepted, or the available history does not
  prove otherwise. Reconciliation refuses to remove the job or its keys.
- `none`: there is no queued operation to resolve.

Elapsed local time, a rejected HTTP response, and an absent parent are never
treated as cancellation proofs. A still-valid operation requires directory-side
resolution first, such as accepting the intended operation or an authorized
competing operation. This CLI does not submit recovery forks. Queue deletion
fences both successful and failed outcomes from stale local workers; the audit
proof addresses already in-flight remote submissions.

## Limits and failures

The directory must still publish this PDS endpoint and service type. The signing
key must match the current or queued private key, and a current or queued control
key must remain authorized. Tombstones, external migrations, unknown keys,
unresolved jobs, corrupt required material, unproved custom handles and handles
reserved by another account leave local state untouched. Provisioning accounts
and prepared migration destinations must finish their existing flow first.

The directory remains trusted for log completeness, freshness and timestamps.
Remote PLC state and PostgreSQL cannot be committed atomically; another authorized
client can publish a new directory operation after the final audit fetch. Keep
other identity writers coordinated during recovery and inspect again afterward.
Public identity caches may need explicit refresh or TTL expiry. Reconciliation
does not restore discarded historical private keys from backups or import an
external recovery key. Deployed public-directory recovery remains unverified.

Real TLS/PostgreSQL tests exercise external changes, queued-key adoption, safe
supersession, key and handle checks, transactional rollback, retry receipts and
stale workers. The pinned `@did-plc/lib` oracle independently checks delayed
operation authority against representative normal and recovered audit histories.
