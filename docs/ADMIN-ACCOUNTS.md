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
Recovery when every authenticator and recovery code has been lost remains a
separate, unimplemented operator procedure.

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

## Verification

HTTP/PostgreSQL tests cover administrative authorization, invalid input, password
and email recovery, uniqueness rollback, OAuth/legacy revocation, TOTP/passkey
preservation, retained moderation/deactivation state, blocked identity operations,
idempotent deletion, durable S3 cleanup locators and account isolation. A concurrent
write test verifies that a request with old credentials cannot commit after
recovery. The full suite retains the owner deletion and S3 worker recovery tests,
and observes all three endpoint responses with the pinned upstream validator.
