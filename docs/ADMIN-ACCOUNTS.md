# Administrative account recovery and deletion

Set `PDS_ADMIN_PASSWORD` to enable administrative XRPC routes. All three routes
below require HTTP Basic authentication with username `admin`; account sessions,
app passwords, service tokens and OAuth grants cannot authorize them. Unset the
password to disable administrative access. Use HTTPS outside loopback development.

| POST endpoint | JSON input | Result |
| --- | --- | --- |
| `com.atproto.admin.updateAccountPassword` | `{"did":"did:…","password":"…"}` | Replace the primary password |
| `com.atproto.admin.updateAccountEmail` | `{"account":"handle.or.did","email":"new@example.com"}` | Replace the recovery email |
| `com.atproto.admin.deleteAccount` | `{"did":"did:…"}` | Delete local account state |

Successful procedures return HTTP 200 with an empty body. Inputs use the pinned
[official administrative Lexicons](https://github.com/bluesky-social/atproto/tree/7a857989751ae31518509d69ab7194a922064f3d/lexicons/com/atproto/admin).
Password length follows this server's existing 8–1,024 character policy. Email
updates accept a DID or current handle, normalize handles and email to lowercase,
validate email syntax and enforce database uniqueness. A conflicting address
returns `InvalidRequest` and rolls back the entire change. Neither endpoint
requires proof from the old email address or a configured email Worker.

## Credential recovery

Both credential operations revoke all legacy sessions and app passwords, remove
outstanding email proofs and queued messages to the old address, and invalidate
pending WebAuthn ceremonies. They advance the account's security version so
existing OAuth access/refresh tokens, authorization codes and authenticated
consent interactions cannot authorize further work. OAuth replay evidence remains
stored under its normal retention policy. Already-dispatched email may still
arrive, but its proof can no longer be used.

A password update preserves confirmed email and email-factor settings. An email
update always marks the address unconfirmed and disables email-based 2FA, including
when the operator submits the same address. Both operations preserve enrolled
TOTP, recovery codes and passkeys. A recovered password still requires the enrolled
factor at login. Users can request confirmation after email service is available;
these admin operations do not enqueue a new email automatically.

Active, deactivated and taken-down accounts can be recovered. Recovery preserves
activation and moderation state: it cannot remove a takedown or activate an
account. Unknown/deleted accounts return `AccountNotFound`. Provisioning accounts
return HTTP 409 `RegistrationPending` until identity registration finishes.
Recovery when every authenticator and recovery code has been lost uses the
explicit local operator procedure below.

## Recovery after losing every authenticator

The local CLI supports an owner who cannot use their enrolled authenticators or
recovery codes. It requires direct database access; a user session, OAuth grant,
email token or `PDS_ADMIN_PASSWORD` alone cannot invoke it. It does not need the
master key or an email Worker. No additional public XRPC endpoint is mounted.
Operator verification of the rightful owner takes place through the deployment's
support process before this command is used.

Inspect the current account status, security version and factor counts:

```sh
mise exec -- clojure -M:account-admin status "$DID"
```

Supply a different primary password through the `PDS_RECOVERY_PASSWORD`
environment variable using your secret-management process. It must contain
8–1,024 characters. The password is never a command argument, part of a receipt,
or printed by the CLI. Pass the exact `securityVersion` from `status` and an
opaque support reference (1–128 ASCII letters, digits, `.`, `_`, `:`, `/`, or `-`,
starting with a letter or digit):

```sh
mise exec -- clojure -M:account-admin recover-authenticators "$DID" "$SECURITY_VERSION" "support-123"
```

One transaction replaces the primary password, disables email 2FA, deletes
pending/confirmed TOTP enrollment and recovery codes, and removes all registered
passkeys and their WebAuthn user handle. It revokes legacy sessions and app
passwords, deletes outstanding email proofs/queued messages and WebAuthn
ceremonies, and advances the account's security version. Existing OAuth grants,
refresh tokens, authorization codes, consent and account-browser sessions are
invalidated by that version. OAuth/browser rows retain their normal replay and
cleanup history; they cannot authorize new work. Already-dispatched email cannot
be recalled, but its old proof is invalidated.

The account's DID, handle, email/confirmation state, repository, blobs, preferences,
public/private identity keys and pending PLC work remain intact. Deactivation and
takedown remain in force. An active owner can sign in with the replacement password
and enroll new authenticators in `/account/security`; the reset does not enroll a
replacement automatically. Provisioning accounts must finish registration first.
Unknown/deleted accounts cannot be recovered.

A successful result includes the DID, previous/resulting security versions,
support reference, PostgreSQL role, removed-factor counts and completion time.
This credential-free receipt commits with the recovery. The database role
identifies the database credential, not necessarily an individual human; keep
operator attribution in the referenced support record. Do not put passwords,
recovery codes or personal details in the reference.

A changed security version returns `SecurityVersionMismatch` without altering
anything. Inspect the newer state before deciding whether another recovery is
appropriate. Concurrent retries with the same DID, original version and reference
converge on one receipt. Repeating a completed command returns that receipt and
ignores the replacement password: it does not reset credentials again or remove
newly enrolled factors. A different reference for that completed version returns
`RecoveryAlreadyCompleted`. Use `status` for current state and the last receipt;
a historical receipt does not describe later password/factor changes.

The command applies migrations, uses a bounded database pool and returns JSON.
Exit codes are 0 for success, 1 for operational failures, and 64 for invalid syntax.
`--help` opens no dependencies. Any transactional failure rolls back the password,
factor deletion, revocation and receipt together. This online account operation
serializes with owner authentication through the account lock; it must still be
stopped with all other writers during offline master-key or database maintenance.

## Deletion

Administrative deletion uses the same transaction as owner-authorized deletion,
without requiring the owner's password or email proof. It removes repository
ownership/indexes, blob metadata, preferences, sessions, app passwords, email
proofs, TOTP/recovery codes and passkeys. The account retains a deleted tombstone
with no password or email; its DID and reserved handles remain unavailable for
reuse. Repository commit/sync replay entries are removed and one deleted-account
event is appended. This does not erase copies already held by other services or
historical unowned blocks awaiting garbage collection.

S3 objects are scheduled in the durable deletion queue using their existing
bucket/key locators; network deletion happens after the account transaction
commits. Another account's copy of the same blob CID is unaffected. PostgreSQL
blob bytes are removed transactionally. See [blob cleanup](S3.md) for worker
behavior and limits.

Deleting an existing tombstone again succeeds without another event or duplicate
cleanup jobs. An unknown DID returns `AccountNotFound`. A pending identity update
returns HTTP 409 `IdentityUpdatePending`; an unfinished registration returns
`RegistrationPending`. Resolve those operations before deletion to avoid removing
credentials while an external directory operation is still in flight. Deletion
does not submit a PLC tombstone or otherwise alter the external DID directory.
Credential-free authenticator-recovery receipts remain alongside the account
tombstone for operator audit; they contain no password, email, key or recovery code.

## Verification

HTTP/PostgreSQL tests cover administrative authorization, invalid input, password
and email recovery, uniqueness rollback, OAuth/legacy revocation, TOTP/passkey
preservation, retained moderation/deactivation state, blocked identity operations,
idempotent deletion, durable S3 cleanup locators and account isolation. A concurrent
write test verifies that a request with old credentials cannot commit after
recovery. The full suite retains the owner deletion and S3 worker recovery tests,
and observes all three endpoint responses with the pinned upstream validator.

Local recovery tests also exercise actual TOTP/passkey enrollment, revoked browser
and OAuth access, owner reenrollment, stale-version rejection, retained inactive
status and PLC jobs, atomic rollback, concurrent retries and a waiting browser
without a lock-order deadlock. A separate JVM runs the shipped CLI without a
master key, admin password or email configuration. Operator identity-verification
policies and hardware-authenticator interoperability remain deployment concerns.
