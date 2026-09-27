# Hosted identities

Set `PDS_HOSTNAME=pds.example.com`, `PDS_PUBLIC_URL=https://pds.example.com`,
`PDS_USER_DOMAIN=example.com`, and `PDS_ENABLE_SIGNUP=true` to provision handles
such as `alice.example.com`. Configure wildcard DNS and HTTPS reverse proxying to
this server, preserving the Host header. The service hostname is reserved.

## DID methods and resolution

Set `PDS_DID_METHOD=plc` to create portable **did:plc** identities. This requires
an HTTPS public URL and a non-reserved user domain. The default `web` mode creates
**did:web** accounts tied to their hostname and supports local development.
The PDS publishes `/.well-known/atproto-did` for hosted handles and
`/.well-known/did.json` at each did:web account's original hostname. The
identity endpoints resolve local and remote handles, `did:web`, and `did:plc`.
Remote handles use DNS TXT first and HTTPS fallback. `resolveIdentity` and
`refreshIdentity` return a bidirectionally verified handle, or `handle.invalid`.
Set `PDS_PLC_URL` to an HTTPS directory origin (default `https://plc.directory`).
Remote requests use public addresses, verified TLS, bounded bodies and deadlines;
local web identities resolve directly from PostgreSQL. PLC resolution fetches and
verifies the directory's full signed audit history, then derives the current DID
document, including for hosted accounts. External migration, recovery and rotation
therefore supersede the local snapshot. Invalid audits fail without a stale local
fallback; timestamps and history freshness still rely on the directory. Audits
are bounded to 4 MiB and 10,000 operations, with redirects disabled. Public remote
resolution uses a [bounded five-minute cache](IDENTITY-CACHE.md), configurable
or disabled through environment settings. `refreshIdentity` forces fresh lookup
and invalidates related cached bindings. Hosted database reads and internal
security-sensitive resolution remain uncached. At most 32 concurrent requests
enter the public identity resolver per server instance.
External end-to-end migration conformance remains unverified. The default localhost
identities are development-only.

## PLC signup and managed keys

PLC signup persists separate encrypted repository and rotation keys, the signed
genesis operation, and the handle/email/invite reservation before submitting to
the directory. The optional `recoveryKey` in `createAccount` is a public `did:key`
placed ahead of the server rotation key; retain its private key separately.
No session, repository event, or confirmation email is issued before the signed
directory audit log confirms registration. A `503 RegistrationPending` response
means the reservation remains durable: retry with the same credentials and
recovery key, or sign in after the background worker finishes. Retryable failures
use exponential backoff from 5 seconds to 1 hour; expired worker leases resume the
same operation after restart. Permanent failures remain reserved for an explicit
signup retry; inspect `plc_identities.status` and `last_error` for sanitized status.
Operators can [rotate managed control and repository signing keys](KEY-ROTATION.md)
with `clojure -M:identity`. Control-key rotation preserves recovery priority.
The [recovery-key command](PLC-RECOVERY-KEYS.md) replaces, reorders or removes
account-held public recovery keys while retaining PDS control and durable retries.
Owners can also manage these public keys at `/account`, with email verification
and saved-change retries.
The [signed recovery CLI](PLC-RECOVERY.md) verifies and submits external
recovery forks without requiring the PDS database or private keys. Local adoption
uses the separate reconciliation procedure.
Signing-key rotation creates a new signed commit over the same records and emits
identity/sync checkpoints. PLC changes resume through the durable identity worker;
hosted web signing-key changes commit atomically.

## Handle updates

Authenticated `com.atproto.identity.updateHandle` accepts a hosted handle or a
custom domain whose DNS/HTTPS handle proof resolves to the account's DID. A web
account keeps its original DID hostname reserved and serving its document after
renaming. PLC changes reserve the new handle, sign against the latest verified
directory audit, and commit the local handle and identity event after directory
confirmation. Unrelated DID fields are preserved; changed signing keys or PDS
endpoints require migration instead. Custom-domain proof is rechecked before
submission. A `503 IdentityUpdatePending` leaves a durable job: retry the same
handle or let the background worker finish. Pending jobs block a different handle
change and account deletion. Permanent conflicts retain the reservation. The local
[PLC reconciliation CLI](PLC-RECONCILIATION.md) can adopt compatible external
changes and safely superseded jobs using explicitly reviewed operation CIDs.
Inspect `handle_updates.status` and `last_error` for sanitized status.
`getRecommendedDidCredentials` returns the account's public repository key, PDS
endpoint, handle and available rotation keys.

## Owner-signed PLC operations

For outgoing PLC migration, call `com.atproto.identity.requestPlcOperationSignature`
with a primary session to enqueue a 30-minute email code through the configured
Worker. Pass that code as `token` to `com.atproto.identity.signPlcOperation`, with
any replacement `rotationKeys`, `alsoKnownAs`, `verificationMethods`, or `services`.
Omitted fields retain the latest verified directory values. The code is bound to
the account and email address and consumed once, atomically with signing; failed
validation or unavailable directory access leaves it usable until expiry. The
response contains a signed successor operation. It does not submit the operation
or change local credentials. App-password sessions cannot request or use these
codes. Existing primary sessions can use this flow while deactivated or taken
down; restricted login/recovery for taken-down accounts remains pending. Pending
identity updates must finish first.

`com.atproto.identity.submitPlcOperation` accepts a signed operation that retains
this account's current handle, local repository key, server rotation key, and
configured PDS service endpoint. It verifies authorization against the current
directory audit before queueing, persists the operation in `handle_updates` with
`operation_kind='submit'`, and confirms directory acceptance before updating local
metadata and publishing an identity event. Identical retries reconcile without
duplicate events. A `503 IdentityUpdatePending` uses the same durable retry and
lease behavior as handle changes. Deactivated primary sessions can submit without
activating the account. This endpoint supports normal successors and already
accepted operations. Externally signed recovery forks use the separate
[recovery CLI](PLC-RECOVERY.md) and explicit local reconciliation.
