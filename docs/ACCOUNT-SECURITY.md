# Optional account authentication

Authenticator-app TOTP verification and persistence are implemented. Passkeys and
browser management/enrollment pages are still being built. There is no public
TOTP enrollment endpoint yet; internal enrollment functions must not be mounted
without recent primary authentication and same-origin/CSRF protection.

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

## Remaining work

- WebAuthn passkey registration and sign-in with required user verification,
  exact RP/origin validation, durable challenges and credential lifecycle.
- Browser settings with recent primary authentication, CSRF protection, TOTP
  provisioning, recovery-code display and secure removal for both methods.
- Passkey/OAuth/management integration, full browser ceremony tests and recovery
  behavior when authenticators are lost. These features are not enabled yet.

References: [RFC 6238](https://www.rfc-editor.org/rfc/rfc6238.html),
[RFC 4226](https://www.rfc-editor.org/rfc/rfc4226.html),
[Google Authenticator URI format](https://github.com/google/google-authenticator/wiki/Key-Uri-Format),
[Yubico Java WebAuthn server](https://developers.yubico.com/java-webauthn-server/).
