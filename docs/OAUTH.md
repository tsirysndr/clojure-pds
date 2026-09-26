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

## Client metadata and redirect policy

`pds.oauth.client/resolve!` fetches the client metadata URL using the shared guarded
HTTPS client. Client IDs require HTTPS without a port, credentials or fragment;
the optional development convention is described below. Metadata and JWKS fetches
accept only HTTP 200 JSON objects, reject encoded responses and redirects, and
carry no ambient cookies or authorization. Each body is limited to 64 KiB; each
exchange has a five-second deadline, with ten seconds shared by the two possible
fetches. Sixteen resolutions may run concurrently per resolver. No database
transaction is held across these requests. Resolution is uncached so removed
client keys are not retained by a metadata cache.

Validation binds the document's exact `client_id`, requires authorization-code
support, the `atproto` scope and DPoP, and supports public clients (`none`) and
confidential clients (`private_key_jwt`). The default application type is `web`.
Only the supported authorization-code/refresh grants and ES256 client assertion
algorithm are accepted. Confidential clients publish either inline JWKS or an
HTTPS JWKS URL. Public P-256 keys are validated and thumbprinted; duplicate key IDs,
private key material and unusable keysets are rejected. Extra unsupported public
keys may coexist with usable ES256 keys. Documents, scopes, redirect lists and
keysets have explicit size/count limits.

Web redirects use HTTPS, omit explicit default ports and match a registered URI
exactly, including path and query. Native clients may use a same-origin HTTPS
callback or a custom scheme equal to their client hostname in reverse order,
followed by a single slash. Optional metadata display fields are validated, but
remain untrusted: the future consent interface must not present an arbitrary
client name or logo as verified identity.

`http://localhost` (also `/`, without a port) produces virtual public-client
metadata with no HTTP fetch. Optional `scope` and repeated `redirect_uri` query
parameters configure it. Defaults allow `http://127.0.0.1/` and `http://[::1]/`.
Only those literal loopback hosts may be used; the callback port may vary, while
host, path and query must match. A callback hostname of `localhost` is not a
substitute for a loopback literal. OAuth parameter decoding rejects malformed
percent escapes, invalid UTF-8 and duplicate scalar parameters, with a 16 KiB and
64-field bound.

Unit and real TLS tests cover web/native/development clients, invalid client IDs,
redirect substitution, metadata/JWKS changes, body limits, status/MIME failures,
private socket addresses, certificate hostname failures, cookie suppression,
redirect rejection and permit release. These primitives feed the client assertion checks below; OAuth endpoints remain
disabled until the authorization/token flow is complete.

## Confidential-client authentication

`pds.oauth.client-auth/verify!` validates `private_key_jwt` assertions against the
freshly resolved client keyset. It requires the JWT-bearer assertion type, ES256,
a nonempty key ID, the exact client ID in `iss` and `sub`, the authorization-server
origin in `aud`, and fresh `iat`, `exp` and `jti` claims. Audience arrays are
supported when they contain the server origin. Optional `nbf` is enforced.
Expiration is mandatory, the maximum assertion lifetime is five minutes, and
issue timestamps allow at most 30 seconds of future clock skew. The JWT type may
be omitted or `JWT`; DPoP proofs cannot substitute for client assertions. Embedded
key material never overrides the published keyset. Both JOSE signature S forms
remain supported.

Public clients return a `none` binding and cannot become confidential by supplying
an assertion. Shared client secrets are rejected. A confidential binding retains
client ID, method, key ID, algorithm and thumbprint. Passing an existing binding
requires an exact match, preventing key replacement, key renaming and method
downgrades during a session. `binding-current?` checks whether refreshed metadata
still advertises the bound key; the forthcoming session lifecycle must revoke
sessions whose keys have disappeared. No session revocation route is claimed yet.

Migration 025 stores hashes of client IDs and jti values with assertion expiration.
The composite unique index prevents concurrent reuse across every key belonging
to a client. Expiration is checked again before consumption, and cleanup removes
at most 1,000 expired entries per successful acceptance. A committed assertion
survives connection reopening; subsequent application rollback cannot revive it.
Invalid signatures or bindings never consume a ledger entry.

OAuth endpoint adapters should use `accept-request!`: it verifies the DPoP nonce
and both proofs before atomically consuming their replay entries in PostgreSQL.
A nonce challenge leaves the assertion available for the retry. If either proof
has already been used, neither new entry commits. Successful authentication commits
before grant logic; a failed grant requires fresh proofs. Metadata resolution
stays outside the transaction. The separate `accept!` helper authenticates only
the client assertion and does not satisfy OAuth's DPoP requirement by itself.

Tests exercise claim/type/audience/time boundaries, key binding and substitution,
key removal through real HTTPS JWKS resolution, concurrent and re-signed replay,
client isolation, rollback, expiry cleanup, and atomic DPoP/assertion acceptance.
The assertion profile follows [RFC 7523](https://www.rfc-editor.org/rfc/rfc7523.html).
Unlike a legacy allowance in the pinned reference provider, assertions without
`exp` are rejected as required by that RFC.

## Remaining steps

1. Authorization server/resource metadata and CORS, pushed requests, mandatory
   PKCE with challenge reuse prevention, and client/DPoP binding.
2. Browser authorization, primary-account login, CSRF protection, consent and
   exact redirect handling; one-use authorization codes and issuer responses.
3. Opaque DPoP-bound access/refresh tokens, refresh rotation/replay revocation,
   client key revalidation, revocation endpoints and session lifecycle integration.
4. Resource-server authentication, permission scopes/sets, service proxy and
   getServiceAuth authorization, nonce headers, and end-to-end reference clients.

Sources: [AT Protocol OAuth profile](https://atproto.com/specs/oauth),
[RFC 9449 DPoP](https://www.rfc-editor.org/rfc/rfc9449.html),
[RFC 7638 JWK thumbprints](https://www.rfc-editor.org/rfc/rfc7638.html), and
[RFC 3986 URI normalization](https://www.rfc-editor.org/rfc/rfc3986.html).
Consulted 2026-09-27.
