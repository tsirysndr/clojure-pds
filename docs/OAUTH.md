# OAuth implementation series

OAuth login and OAuth-authorized XRPC access are not enabled yet. Legacy session
endpoints retain their existing behavior. The following foundations are
implemented for the authorization server and resource server; they do not by
themselves establish OAuth compatibility.

## Proof verification and durable replay protection

`pds.oauth.jose` parses bounded compact ES256 JWTs, validates public P-256 JWKs
(including coordinates on the curve), computes RFC 7638 thumbprints, and verifies
JOSE signatures. Symmetric keys, private key material, unsupported algorithms,
critical extensions and unencoded payloads are rejected. Key identifiers and
other optional fields do not change a thumbprint, and key URLs are never fetched.
Both ECDSA S forms are accepted for JOSE; repository/PLC low-S rules stay unchanged.

`pds.oauth.dpop/verify!` checks the proof type, signature, required claims,
request method/URL, issue time, server nonce, and any optional expiration or
not-before claims. If an access token is supplied, its stored key thumbprint and
its SHA-256 `ath` binding are required. Token/PAR requests can enforce a previously
bound thumbprint. Proofs are limited to 16 KiB and jti values to 256 UTF-8 bytes.
JSON depth and trailing-data checks are inherited from the bounded request parser.

HTTP target matching omits query/fragment components and normalizes scheme/host,
default ports, unreserved percent escapes and dot segments. Repeated slashes,
reserved escapes, path case and nondefault ports retain their meaning. Future
HTTP adapters must construct the target from the configured public origin and
request path, never an untrusted Host or forwarding header.

Server nonces use purpose-separated HMAC-SHA-256 with the master key and public
origin. They rotate every two minutes and expire five minutes after the rotation
window started, allowing overlap during concurrent requests. They survive process
restarts and work across instances with the same master key/public origin and
synchronized clocks. No Redis or per-session nonce memory is required. Proof
`iat` values must be within the previous five minutes or at most 30 seconds ahead.
Missing, invalid or stale nonces yield `use_dpop_nonce`; other verification failures
yield `invalid_dpop_proof` internally. HTTP error/nonce headers will be added with
the OAuth endpoint adapters.

`pds.oauth.proof-store/accept!` verifies and commits the proof's key thumbprint and
hashed jti to PostgreSQL before business logic proceeds. The unique key spans
endpoints and nonce rotations; changing signature encoding cannot bypass replay
checks. Expiration is rechecked at consumption. A later application rollback does
not revive an accepted proof. A fresh proof must accompany a retried request.
Database failures propagate; there is no in-memory replay fallback. Cleanup removes
at most 1,000 expired rows per successful acceptance. Only hashes and expiration
are persisted, not proof JWTs or access tokens. Migration 024 adds this ledger.

Tests cover malformed proofs/keys, key/token/target substitution, normalization,
nonce rotation and expiry, concurrent duplicate acceptance, re-signed proofs,
application rollback, bounded cleanup, and replay rejection by a fresh JVM.
Node's independent crypto implementation checks signatures and thumbprints and
generates both ECDSA signature forms for the Clojure verifier.

## Remaining steps

1. Client metadata and JWKS fetching/validation, public and confidential client
   policy, client assertion verification, and development/native redirect rules.
2. Authorization server/resource metadata and CORS, pushed requests, mandatory
   PKCE with challenge reuse prevention, and client/DPoP binding.
3. Browser authorization, primary-account login, CSRF protection, consent and
   exact redirect handling; one-use authorization codes and issuer responses.
4. Opaque DPoP-bound access/refresh tokens, refresh rotation/replay revocation,
   client key revalidation, revocation endpoints and session lifecycle integration.
5. Resource-server authentication, permission scopes/sets, service proxy and
   getServiceAuth authorization, nonce headers, and end-to-end reference clients.

Sources: [AT Protocol OAuth profile](https://atproto.com/specs/oauth),
[RFC 9449 DPoP](https://www.rfc-editor.org/rfc/rfc9449.html),
[RFC 7638 JWK thumbprints](https://www.rfc-editor.org/rfc/rfc7638.html), and
[RFC 3986 URI normalization](https://www.rfc-editor.org/rfc/rfc3986.html).
Consulted 2026-09-27.
