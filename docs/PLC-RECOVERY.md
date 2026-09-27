# Submit an externally signed PLC recovery fork

The recovery CLI publishes an already-signed recovery operation and verifies the
resulting directory history. It works independently of the PDS database and
`PDS_MASTER_KEY`, so loss of the server's control key does not prevent submission.
The signing authority comes from the external recovery key's signature. The CLI
never asks for that private key and does not generate a replacement signature.

This is different from [changing the account's public recovery-key list](PLC-RECOVERY-KEYS.md),
[recovering lost login factors](ADMIN-ACCOUNTS.md), and
[adopting directory history locally](PLC-RECONCILIATION.md).

## Prepare and inspect

Use an offline signer or an external recovery tool to produce a signed PLC
operation. Save the operation object itself as JSON, with its `sig`, not a wrapper
such as `{"operation": ...}`. Keep this exact file for retries. Do not put private
key material in it. The CLI rejects extra fields, unsigned operations, oversized
input, deep nesting and trailing JSON. The JSON file limit is 65,536 bytes; the
canonical signed PLC operation limit remains 7,500 bytes.

For a recovery fork, `prev` must refer to a canonical ancestor of the current head.
The operation must be signed by a key with higher priority than the signer of the
first operation being replaced, using the key ordering at that ancestor. The
[PLC recovery rules](https://web.plc.directory/spec/v0.1/did-plc) allow this within
72 hours of that first replaced operation. Both P-256 and secp256k1 signatures,
legacy genesis ancestry, recovery from tombstones, and signed recovery tombstones
are supported. A normal successor or genesis belongs to the ordinary identity
workflow and cannot be newly submitted through this command.

Set `PDS_PLC_URL` to the account's actual HTTPS directory origin if it differs from
`https://plc.directory`. Redirects are never followed. The shared networking layer
applies public-address checks, TLS verification, bounded responses and deadlines.
No database connection or server master-key lease is opened.

```sh
mise exec -- clojure -M:plc-recovery inspect "$DID" recovery.json
```

For a new eligible recovery, JSON output has `state: ready`, `remoteCid`,
`operationCid`, `previousCid`, `signer`, `expiresAt`, `nullifiedCids` and
`directoryData`. Review the signer, affected history, resulting rotation keys,
repository signing key, handle aliases and service endpoints. `directoryData`
is null for a tombstone. The preview checks the full audit and the recovery
window against the local clock; the directory controls its actual acceptance
time. Clock skew can prevent local preflight or cause directory rejection.

## Submit the reviewed operation

Pass both CIDs from the inspection and the same signed file:

```sh
mise exec -- clojure -M:plc-recovery submit "$DID" "$REMOTE_CID" "$OPERATION_CID" recovery.json
```

Before posting, the CLI checks the operation CID, refetches and verifies the
complete audit, requires the reviewed head for an unseen operation, and reruns
signature, priority and recovery-window checks. It sends the existing signed
operation once. After any HTTP outcome, including a dropped connection or error
response, it fetches and verifies the audit again. HTTP success alone is never
reported as recovery confirmation.

Results distinguish:

- `confirmed`: the signed operation is the current canonical head.
- `accepted`: it is canonical history with later descendants. Those descendants
  remain in effect; `directoryData` describes the current head.
- `superseded`: the exact operation is recorded but nullified. It will not be
  reposted. Inspect current history and prepare a new authorized operation if needed.

These observed results include `remoteCid` and `auditNullifiedCids`, the complete
set of currently nullified audit CIDs. The preview's `nullifiedCids` instead lists
only the branch the proposed recovery would replace. A retry returns the observed
state even if the original reviewed head has since changed. Known CIDs are never
posted again. Unseen operations with a different current head require a new
inspection, rather than automatically rewriting newly observed history.

Exit codes are 0 for an eligible inspection or accepted/confirmed operation, 1 for
supersession or a non-retryable failure, 2 for an unavailable directory or an
unconfirmed outcome, and 64 for command usage errors. Failures contain a sanitized
reason; remote response bodies, local file contents and environment values are
not printed. After an uncertain outcome, inspect or retry the same signed file
before creating another operation. This is an explicit command, with no unattended
retry worker; the file is the durable signed intent and the directory audit is the
acceptance record.

## Concurrent changes and local adoption

The PLC POST API has no conditional-write field. Comparing the remote head before
POST cannot prevent another operation being accepted between those requests. A
valid recovery may nullify additional lower-priority descendants in that interval.
The post-submission audit reports the actual outcome; the preview is not an atomic
reservation. Directory acceptance timestamps and log completeness/freshness remain
trusted assertions. The signed operation and recomputed recovery decisions are
verified locally.

Submission does not change local accounts, handles, sessions, encrypted keys,
repositories, events or pending identity jobs. This also allows recovery that moves
the public identity to a different PDS or uses keys unavailable to the former
server. Follow the destination's migration procedure in that case.

When the resulting identity still belongs on this PDS and uses available keys,
run the [explicit reconciliation procedure](PLC-RECONCILIATION.md). It verifies
that queued operations are accepted or safely superseded before deleting them,
retains required key material, and fences stale workers. Keep pending jobs and
private keys until that verification succeeds. Recovery cannot reconstruct lost
repository content or private keys.

## Verification

Pure and real TLS tests cover priority, expiry boundaries, both curves, legacy
ancestry, tombstones, invalid signatures/audits, operation/head mismatches,
rejections, redirects, dropped/error responses, observed descendants and
supersession, exact retries and concurrent directory changes. The pinned PLC
reference library independently checks recovery eligibility and nullification.
PostgreSQL integration tests recover a changed identity, preserve local and queued
keys until explicit adoption, fence an old worker, and verify public migration
without mutating the former PDS. Deployed directory and external signer
interoperability remain unverified.
