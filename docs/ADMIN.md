# Invites and administration

Set `PDS_REQUIRE_INVITE_CODE=true` to require an invitation during signup;
`PDS_ENABLE_SIGNUP` must also be enabled. Set `PDS_ADMIN_PASSWORD` to a random
secret of at least 16 characters to enable admin endpoints (unset by default).
Use HTTP Basic authentication with username `admin` over your HTTPS origin.

## Invite codes

`com.atproto.server.createInviteCode` takes `useCount` and optional `forAccount`;
`createInviteCodes` creates a bounded batch. Ordinary session tokens cannot call
these endpoints. Invite redemption is transactional, including concurrent final uses.
Accounts list their codes with `getAccountInviteCodes`; `includeUsed=false` filters
unavailable codes. Admins list every code with `com.atproto.admin.getInviteCodes`
(`sort=recent|usage`, limit 1–500, opaque keyset cursor) and can invalidate codes
through `disableInviteCodes` by code or owner.
`disableAccountInvites`/`enableAccountInvites` retain the future-grant
policy flag and note; existing codes remain usable. Automatic invite grants are
disabled, so `createAvailable` currently creates no additional codes.

## Account administration

[Administrative account recovery](ADMIN-ACCOUNTS.md) supports password and
email updates and account deletion. `com.atproto.admin.updateAccountHandle`
changes an account's handle through the same reservation, external-proof and
durable PLC flow as the owner endpoint, including for deactivated accounts.
Credential recovery revokes existing sessions and email proofs while preserving
enrolled TOTP and passkeys. A separate local
`clojure -M:account-admin recover-authenticators` command handles loss of every
factor with a new password, security-version checks and an audit receipt.

Admins can inspect accounts with `com.atproto.admin.getAccountInfo`, fetch
deduplicated batches with `getAccountInfos` (unknown DIDs are skipped), search
by exact case-insensitive email with `searchAccounts` (DID-keyset pagination,
limit 1–100, default 50), and send bounded operator email to an account's
registered address with `sendEmail` (queued on the durable outbox; requires
configured email delivery).

## Takedowns

Admins manage account takedowns through `getSubjectStatus`/`updateSubjectStatus`
using a `com.atproto.admin.defs#repoRef` subject. Takedown blocks content, login,
refresh, and invitations issued by the account. Removing it preserves a preexisting
deactivation; users cannot lift a takedown with `activateAccount`. Changes produce
ordered account-status events. [Record and blob takedowns](MODERATION.md)
use strongRef/repoBlobRef subjects. Record flags hide indexed reads while
preserving signed sync data; blob flags block downloads, reuploads and new
references with both PostgreSQL and S3 storage.
