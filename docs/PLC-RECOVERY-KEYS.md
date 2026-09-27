# Account-held PLC recovery keys

An established managed PLC account can add, replace, reorder or remove its
account-held recovery keys without changing its repository signing key or PDS
control key. The operator command uses the durable identity queue, verifies the
directory before preparation, and records completion only after confirmation.
Account owners can also use the existing email-authorized XRPC signing flow.

This is distinct from TOTP recovery codes, lost-authenticator account recovery,
repository signing-key rotation and the server's encryption master key. PLC
recovery keys control the DID itself. Their private keys should be generated and
backed up outside this PDS; this command accepts only public `did:key` values and
does not upload, generate or store those private keys. Key syntax validation does
not prove possession of a working private-key backup.

## Operator procedure

Upgrade the server and identity workers to migration 043 before using the command.
An older worker does not know how to complete a `recovery` queue job. Use the
server's database, public-origin and master-key configuration. Local operator
access authorizes this command; it does not use an account session or send email.
The CLI holds a master-key maintenance lease like the other identity commands.

Inspect the managed account's current key list and operation CIDs:

```sh
mise exec -- clojure -M:identity inspect-plc "$DID"
```

Resolve any pending operation or external identity mismatch first. Supply the
confirmed operation CID explicitly and the complete desired list of recovery
public keys, from highest to lowest priority:

```sh
mise exec -- clojure -M:identity set-recovery-keys "$DID" "$EXPECTED_CID" "$RECOVERY_KEY_1" "$RECOVERY_KEY_2"
```

The list can contain zero to four distinct P-256 or secp256k1 `did:key` values.
The PDS's current control key is appended automatically in the lowest-priority
position; including it in the recovery list is rejected. Other aliases, services
and verification methods remain unchanged. Existing keys omitted from the list
are removed. Changing the order changes authority. To remove all account-held
recovery keys while retaining PDS control:

```sh
mise exec -- clojure -M:identity set-recovery-keys "$DID" "$EXPECTED_CID"
```

If the desired complete list already matches the fresh directory state, the
command returns `state: unchanged`, makes no directory submission, emits no event
and creates no receipt. That CID remains usable for a later actual change.

Otherwise the command stores one signed successor operation and processes it
through the identity worker. `pending`/`working` exits with code 2, `failed` with
code 1, and `completed`/`unchanged` with code 0. Retry the same command with the
original expected CID and key order. Retries reuse the exact stored operation;
an explicit retry makes failed/backed-off jobs due without stealing a live lease.
Different requested keys at the same pending or completed request are refused.

Confirmation atomically updates the local PLC snapshot, writes a public
`plc_recovery_key_changes` receipt, removes the job and emits one identity event.
No repository commit, signing key, session or account status is changed. Ordinary
repository writes can continue while this job is queued. Other identity changes
and deletion wait until the job is resolved. Active, deactivated and taken-down
accounts are supported; provisioning and prepared migration destinations must
finish their existing flows first.

Receipts include `previousCid`, `operationCid`, ordered `recoveryKeys`, and the
retained PDS `rotationKey`. Retrying a completed request returns its original
receipt even after later identity changes; inspect current directory state when
you need the current list. If another client advances the directory while a job
is pending, [explicit reconciliation](PLC-RECONCILIATION.md) can install compatible
state. It records completion of this key-list request only when the adopted head
contains exactly the requested complete ordered list. A safely superseded request
with different resulting keys gets a reconciliation receipt, not a false key-change
completion receipt; a new request must use the newly confirmed CID.

## Account-owner XRPC flow

Owners do not need operator database access. With a primary session, call
`com.atproto.identity.requestPlcOperationSignature` to request the one-use email
token through the configured email Worker. Read the current recommended
credentials with `com.atproto.identity.getRecommendedDidCredentials`, then call
`com.atproto.identity.signPlcOperation` with the email `token` and the complete
desired `rotationKeys` list, including the retained PDS control key. Omit unrelated
identity fields to preserve them. Submit the returned signed `operation` to
`com.atproto.identity.submitPlcOperation`; retry the same signed operation after
an ambiguous response. These standard endpoints retain their existing session,
email-token, credential and directory-authorization checks. They do not create
operator key-change receipts. Browser key-management controls are not implemented.

## Recovery authority and verification

The [PLC specification](https://web.plc.directory/spec/v0.1/did-plc) gives higher
priority keys a recovery window over changes signed by lower-priority keys. A
server-signed removal does not instantly revoke a removed higher-priority key's
ability to recover that branch. Do not treat this command as immediate compromise
containment. It publishes ordinary successor operations; signing and submitting
an external recovery fork remains separate work. A new key list does not restore
lost private keys or undo already observed external identity operations.

Real PostgreSQL/TLS tests cover both supported curves, maximum key count,
replacement/reordering/removal, no-op behavior, continued repository writes,
unchanged credentials/status, concurrent requests, retry receipts, rejected and
ambiguous responses, rollback, stale workers, local/remote races and reconciliation.
The pinned PLC reference library independently verifies published key lists and
the removed key's recovery authority. Public-directory deployment and external
recovery-tool interoperability remain unverified.
