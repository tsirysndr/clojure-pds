# Sessions and account lifecycle

Passwords use Argon2id. Access tokens expire after 15 minutes; sessions have a
90-day lifetime. Refresh tokens rotate once; replay revokes the session. Password
reset revokes all sessions and app passwords. Email confirmation/reset tokens
expire after 30 minutes and are single-use. Signup queues confirmation if email
delivery is configured.

Already active accounts use `createSession`, not repeated signup. Account deletion
erases local private keys and content but does not tombstone the public PLC DID.

## Activation state

`deactivateAccount` hides repository content and blocks writes. Primary-password
login, refresh, `getSession`, and `activateAccount` remain available; app sessions
cannot manage activation. Identity metadata remains available while deactivated.
State changes enter the durable event log in commit order. Optional `deleteAfter`
is retained as a recommendation; it does not automatically delete the account.

## Email changes and the email factor

Email changes require a primary session. `requestEmailUpdate` sends a proof token
to the current address when it is confirmed; `updateEmail` consumes that proof,
invalidates old-address recovery tokens and other sessions, then queues confirmation
for the new address. The current session remains available to finish verification.
Confirmed addresses can enable `emailAuthFactor` through the same proof flow.
Primary login then returns `AuthFactorTokenRequired` and queues a ten-minute,
one-use token; repeat login with `authFactorToken`. App passwords remain usable
without repeating the email factor. Turning the factor off requires fresh email
proof. Challenges and their outbox entries commit before the challenge response.

## Deletion

`requestAccountDelete` sends a one-use deletion token. `deleteAccount` accepts the
DID, primary password and token, removes sessions and hosted content metadata,
erases email/password data, and records a `deleted` tombstone. The DID/handle stay
reserved. PostgreSQL blob bytes are removed transactionally; S3 bytes use a durable
retry queue. Shared/historical repository-block reclamation is tracked separately
on the storage roadmap.

## App passwords

Primary-password sessions can create named app passwords using
`com.atproto.server.createAppPassword` (optional `privileged: true`). The secret is
returned once and only a purpose-keyed digest is stored. Use it with
`createSession`; access scopes are `com.atproto.appPass` or
`com.atproto.appPassPrivileged` and survive refresh. App sessions cannot create
more passwords. `listAppPasswords` returns metadata only; `revokeAppPassword`
deletes dependent access/refresh sessions immediately. These two endpoints also
allow app sessions for their own account, matching upstream behavior. There is a
limit of 100 app passwords per account. Privileged scope authorizes proxied chat
methods through the shared service-authentication policy.

Optional TOTP and passkey authenticators are covered in
[account security](ACCOUNT-SECURITY.md).
