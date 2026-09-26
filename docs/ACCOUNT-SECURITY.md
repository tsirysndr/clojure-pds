# Optional account authentication

Accounts can be created and optional authenticator-app TOTP and passkeys managed
at `/account`. Signup is shown only when `PDS_ENABLE_SIGNUP=true`, and respects
`PDS_REQUIRE_INVITE_CODE`.
The login, verification and settings screens use locally compiled Tailwind CSS,
with the compact card layout of the selfhosted.social and Witchcraft PDS OAuth
screens, a purple `#8338EC` accent, and system light/dark colors.

## Authenticator applications

The `pds.security.totp` implementation follows RFC 4226/6238, tested against their
published HOTP and SHA-1/SHA-256/SHA-512 TOTP vectors. Account policy uses the broadly
supported SHA-1, six-digit, 30-second format, with standard `otpauth://` provisioning
URIs for applications such as Google Authenticator. A ±one-step clock window is
accepted. Input must be exactly six ASCII digits, including any leading zero.

Migration 028 and `pds.security.factors` provide staged enrollment and confirmation.
Enrollment lasts ten minutes and is bound to the current account security version.
The factor stays disabled until the owner submits a correct code. Secrets are
random 160-bit values sealed with AES-GCM under the server master key, with the
account DID included in authenticated associated data. Confirmed secrets cannot
be replaced by restarting enrollment. Email factors and TOTP are alternative
second-factor choices; switching requires removing the existing factor first.

Confirmation returns ten random 160-bit recovery codes once. PostgreSQL stores
only account-bound hashes. Confirmation and removal invalidate legacy sessions,
remove existing app passwords, and advance the account security version used by
OAuth. Recovery codes can satisfy a sign-in factor or authorize factor removal;
management must also require recent primary authentication. Recovery-code use and
TOTP counters commit with the protected operation, preventing concurrent reuse.

Five failed proofs in a five-minute window block further attempts until the window
expires. This budget is persisted in PostgreSQL and shared across instances and
login paths. A successful proof resets failures. Callers must commit error results
before returning a rejection; throwing inside the transaction would undo the
attempt budget. Internal APIs require transactions and lock the account first.

Primary-password `com.atproto.server.createSession` login requires TOTP or a recovery
code in the existing `authFactorToken` field when enrolled. OAuth interactions use
the same verifier. Missing tokens return a factor-required result, and incorrect
or replayed tokens cannot authenticate. The enrollment confirmation code is already
consumed: wait for the next code before using TOTP to sign in again. App passwords
created after a successful factor-authenticated primary login remain explicitly
delegated credentials, as with the existing email factor.

Password reset preserves the authenticator requirement. Account deletion erases
its secret and recovery codes. The existing deletion protocol still requires its
primary password and one-use email deletion proof. Password recovery does not
silently disable TOTP; users who lose their authenticator need a saved recovery code.

Tests cover encrypted storage, inactive enrollment, credential-change/expiry
invalidation, replay races, throttling, rollback, one-use recovery, legacy HTTP
and OAuth login, session revocation, password reset and deletion.

## Passkey ceremonies

`pds.security.passkeys` uses Yubico Java WebAuthn Server 2.9.0, with explicit
stable dependency pins replacing its open version ranges. Registration requests
require discoverable credentials and user verification, and request no attestation.
ES256, Ed25519 and RS256 are supported. The relying-party ID comes from the host
of the configured public URL; its exact origin is the only permitted origin.
No subdomain or port relaxation is enabled. HTTPS is required, with localhost HTTP
permitted for development. Cross-origin framed ceremonies are rejected explicitly.
This is public passkey support, without a vendor or hardware-attestation trust policy.

Migration 029 stores a random 32-byte account user handle, credential IDs and public
COSE keys, names, counters, transport hints and backup flags. No private credential
key leaves the authenticator or is stored by the server. Identifier-first login is
implemented; username-less autofill is not yet implemented. Account handles can
change without changing the opaque authenticator user handle.

Challenges last five minutes. The server stores the full generated options, the
account security version and hashes of a random ceremony ID and browser binding.
Client-supplied options cannot replace the saved request. All operations require
transactions, with account locking before challenge/credential changes. Each
account may have up to eight pending ceremonies and 20 registered credentials.
Expired challenges are reclaimed in bounded batches. Cryptographically invalid
responses return an error and consume the challenge when the caller commits it.
Wrong browser bindings cannot consume another browser's challenge. Successful
verification and its protected session/credential write must share a transaction.

Assertions verify account ownership, user presence/verification, origin, RP ID,
challenge, signature, and credential counter. Nonzero counters must increase;
authenticators that consistently use zero counters are supported, including synced
passkeys. Backup eligibility cannot change, while backup state updates after valid
assertions. Registration/removal advances the account security version, revokes
legacy sessions and removes app passwords. Pending challenges fail after account
credential/status changes even if a value is changed back. Deletion erases the
account's credentials, user handle and challenges.

Independent Node crypto fixtures generate real registration/assertion responses
for all three algorithms. PostgreSQL tests exercise origin/RP/type/challenge
substitution, missing verification/presence, invalid signatures, cross-account
credentials, malformed inputs, browser binding, expiry, concurrent replay,
transaction rollback, counter/backup state, challenge caps, removal and deletion.
These are software-authenticator tests, not evidence of a completed browser or
hardware passkey integration. Integration tests now require Node (CI pins Node 24).

Management functions must be called behind recent primary or user-verified passkey
authentication, any configured additional factor, and browser CSRF checks. The
assertion verifier returns a principal; it creates no session itself and does not
bypass an enabled TOTP/email factor. The browser controller consumes that principal
and establishes the session in the same transaction.

## Browser security settings

Migration 030 adds persistent, hashed browser sessions. `/account/session` issues
an anonymous cookie; successful primary authentication rotates both cookie and
CSRF token. An enabled TOTP/email factor must be verified before settings become
available, including after passkey authentication. Settings sessions expire after
five minutes; security mutations retain that original deadline and revoke other
browser sessions through the account security version. App passwords cannot be
used for owner authentication.

HTTPS uses a `__Host-` cookie with Secure, HttpOnly and SameSite=Lax. Local HTTP
uses a separate development cookie name. Every mutation requires the configured
public origin, JSON, and a session-bound CSRF token. Responses disallow caching,
framing and external scripts/styles. Private state is never saved in localStorage.
Invalid TOTP attempts and invalid passkey proofs commit their attempt/replay state.

The browser supports account creation without legacy bearer tokens, password and
identifier-first passkey login, passkey naming,
registration and removal, manual authenticator setup, confirmation, one-time
recovery-code display, authenticator removal and switching away from email 2FA.
Registration requests user verification and discoverable credentials. Password
visibility is optional; password/code fields are cleared after submission.

PostgreSQL tests cover browser session rotation, expiry, security-version
revocation, CSRF/origin and cookie enforcement, enrollment, recovery-code login,
passkey login with an additional factor, and durable failed-attempt limits.
The login has also been visually compared with the deployed reference screen.
Real hardware ceremonies and complete browser automation remain to be verified.

## Building the interface

The server serves committed assets; Node is only needed to rebuild CSS:

```sh
npm ci --prefix scripts/ui --ignore-scripts
npm run --prefix scripts/ui build
```

Edit `resources/security/index.html`, `resources/security/app.js` and
`scripts/ui/input.css`, then commit the generated `resources/security/style.css`.
No CDN or React runtime is required. Visual references:
[Witchcraft sign-in](https://pds.witchcraft.systems/account/sign-in),
[selfhosted.social sign-in](https://selfhosted.social/account/sign-in).

## Remaining work

- Mounting the implemented OAuth authorization/consent adapter with discovery and
  session management; full browser ceremony tests.
- Username-less discoverable login, QR provisioning, and recovery when all
  authenticators and recovery codes are lost.

References: [RFC 6238](https://www.rfc-editor.org/rfc/rfc6238.html),
[RFC 4226](https://www.rfc-editor.org/rfc/rfc4226.html),
[Google Authenticator URI format](https://github.com/google/google-authenticator/wiki/Key-Uri-Format),
[Yubico Java WebAuthn server](https://developers.yubico.com/java-webauthn-server/).
