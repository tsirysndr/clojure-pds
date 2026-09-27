# OAuth implementation series

The PDS publishes OAuth discovery, PAR, browser authorization, token and revocation
endpoints. DPoP resource authentication is connected to XRPC with transitional
scopes, direct record/blob/RPC/account/identity permissions, and dynamically
resolved permission sets. Legacy session endpoints retain their existing behavior.
Deployed interoperability remains incomplete.

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
reserved escapes, path case and nondefault ports retain their meaning.
HTTP adapters construct the target from the configured public origin and
request path, never an untrusted Host or forwarding header.

Server nonces use purpose-separated HMAC-SHA-256 with the master key and public
origin. They rotate every two minutes and expire five minutes after the rotation
window started, allowing overlap during concurrent requests. They survive process
restarts and work across instances with the same master key/public origin and
synchronized clocks. No Redis or per-session nonce memory is required. Proof
`iat` values must be within the previous five minutes or at most 30 seconds ahead.
Missing, invalid or stale nonces yield `use_dpop_nonce`; other verification failures
yield `invalid_dpop_proof` internally. HTTP adapters return OAuth errors and nonce
headers, including on rate-limit rejections.

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
redirect rejection and permit release. These primitives feed the client assertion
checks below and the mounted authorization/token flow.

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
still advertises the bound key; the token lifecycle revokes the presented session when its bound key has disappeared.
No public session revocation route is claimed yet.

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

## Pushed authorization requests and PKCE

`pds.oauth.par/push!` resolves current client metadata, validates authorization
parameters, authenticates the client and DPoP proof, and persists an immutable
request snapshot. Responses contain a random 256-bit `request_uri` and a
90-second `expires_in`. PostgreSQL stores only the URI hash, the exact client ID,
validated parameters, the client authentication binding and DPoP thumbprint.
Client assertions, DPoP JWTs and arbitrary extension fields are not stored.
Optional `dpop_jkt` values must match the proof's key.

Only code responses, query response mode and S256 PKCE are accepted. State is
required, redirects must match client metadata, and requested scopes must be
declared by the client. The validator supports `atproto`, the three transitional
scopes, direct permissions and authenticated `include:` permission sets documented
below. Transitional chat additionally requires `transition:generic`. Login hints
and supported prompt values are preserved for
the browser authorization interface.

Migration 026 reserves each PKCE challenge across all clients for 24 hours. The
reservation and PAR row commit atomically; failed inserts leave neither behind.
Proof acceptance commits first, so a failed PAR grant needs fresh authentication
proofs. Concurrent attempts to reuse a challenge admit only one request. Cleanup
removes at most 1,000 expired request rows and 1,000 expired challenge reservations
per successful push. Consuming or expiring a request does not shorten its challenge
reservation. The verifier implements the RFC 7636 S256 vector, canonical challenge
encoding, the 43–128 character verifier grammar, and constant-time hash comparison.

`claim!` consumes a request URI once, for its exact client ID, within the caller's
transaction. It returns the snapshot for creating browser interaction state in
that same transaction. Failed interaction creation rolls the consumption back;
concurrent or repeated committed consumption is rejected. Expired, malformed and
cross-client request URIs cannot be claimed or used as network lookup URLs.

The standalone `par/handler` adapter implements form-encoded POST, strict UTF-8
and duplicate-parameter handling, a 16 KiB body limit, HTTP 201, OAuth JSON errors,
no-store responses, public-client CORS/preflight and a DPoP nonce on every response.
It rejects query parameters and Authorization-header credentials at PAR. Actual
HTTP/TLS tests cover nonce challenge/retry with a confidential client, persistent
bindings, CORS, malformed forms, oversize bodies and method errors. PostgreSQL
tests cover expiry, concurrent submission/consumption, rollback, reopened
connections, global challenge reuse and bounded cleanup.

The adapter is mounted by `pds.app` at `/oauth/par`. References:
[RFC 9126 PAR](https://www.rfc-editor.org/rfc/rfc9126.html) and
[RFC 7636 PKCE](https://www.rfc-editor.org/rfc/rfc7636.html).

## Durable browser interactions and consent

Migration 027 and `pds.oauth.interaction` implement the authorization state machine.
Starting an interaction atomically consumes PAR and persists its immutable snapshot.
Each interaction lasts ten minutes and receives independent random identifiers and
browser secrets; PostgreSQL stores their hashes. CSRF values are purpose-separated
HMACs bound to the browser secret, interaction and a rotating nonce. Authentication
rotates the nonce, so a login form cannot also approve the request. The browser adapter delivers secrets in secure HttpOnly cookies, enforces same-origin
POSTs and renders dynamic content through DOM text nodes.

Login accepts an active account's primary password, respects `login_hint`, and
requires the configured email or TOTP sign-in factor. App passwords cannot delegate new
OAuth authority. The email challenge/outbox commits before a factor-required result;
factor consumption and authenticated interaction state commit together. No legacy
session is created. Login and consent are distinct operations; automatic approval
and account switching within an authenticated interaction are not supported.

Approval freshly resolves client metadata outside the database transaction, then
locks and rechecks the interaction and account. Removed redirects, changed scopes
or changed client keys cannot reuse the original request. A database-maintained
account security version changes on password, email, email-confirmation, email-factor
or status updates. Pending approval remains invalid after a setting is changed back.
Factor enrollment/removal can explicitly advance the version. Token issuance, refresh and access-grant loading check this version too;
resource request authentication is still pending.

Exactly one concurrent decision succeeds. Approval atomically marks the interaction
complete and stores a hashed random code with a 60-second lifetime, the account
version, PKCE challenge, client binding and DPoP thumbprint. Denial issues no code.
Callbacks retain the registered URI's original query and append `state`, `iss` and
`code` or `error=access_denied`. Failed inserts roll back completion. Code redemption
and replay/session revocation are implemented below.
Expired interactions are cleaned in bounded batches when new ones start.

PostgreSQL tests cover cookie/interaction substitution, CSRF rotation, primary-only
login, hints, email-factor delivery and transactional consumption, lifecycle changes,
expiration, concurrent decisions, metadata changes and failed PAR/code inserts.
The browser adapter and pages below implement this flow, mounted at
`/oauth/authorize` and `/oauth/flow/:id`.

## Opaque access and refresh tokens

Migration 031 and `pds.oauth.tokens` persist OAuth sessions and token hashes.
`issue!` redeems authorization codes with the original client ID, exact redirect,
PKCE verifier, client authentication key/method and DPoP key. The account must
remain active with its original security version. Issuance and code consumption
share a transaction; failed token insertion leaves the code available, but never
restores consumed DPoP/client assertions.

The response includes `token_type=DPoP`, `sub`, `scope`, `expires_in`, an opaque
access token and, when declared in client metadata, an opaque refresh token.
Only SHA-256 token hashes enter PostgreSQL. Access tokens last at most five
minutes. Public sessions have a fixed 14-day deadline; confidential sessions have
a fixed 180-day deadline. Refreshing never extends the deadline. Clients without
the refresh grant get a five-minute access-only session.

Refresh rotation is atomic and single-use. Used refresh hashes remain as replay
tombstones for the session lifetime. A correctly authenticated replay revokes the
whole family, including existing access tokens and newly rotated refresh tokens.
A correctly bound code replay also revokes its issued session. Invalid PKCE,
redirects, client assertions or DPoP keys cannot revoke someone else's session.
Revocation commits before `invalid_grant` is raised. Concurrent refresh/code
requests admit at most one issuance and subsequent replays revoke that family;
clients must serialize refreshes. No retry grace window is implemented.

Every token request fetches fresh metadata outside the database transaction.
Confidential sessions stay pinned to the exact original key and method, even
when another published key can authenticate the client. A removed bound key or
removed refresh grant permanently revokes the presented session. Temporary
metadata-fetch failures reject the request without issuing tokens. Credential or
account status changes invalidate access and refresh through `oauth_epoch`.
Refresh may narrow the access token's scope strings, but cannot add strings beyond
the original authorization; omitting scope retains the original grant. The
permissions behind an included set may evolve within its namespace at refresh.

`access-grant!` checks token/session expiry, revocation and account version inside
a caller-owned transaction. It is a storage primitive, **not resource request
authentication**: DPoP `ath`, client metadata revalidation and permission checks
are enforced by the resource middleware below. The standalone token HTTP
adapter provides bounded form parsing, CORS, no-store responses and nonce headers
and is mounted at `/oauth/token`.

Tests exercise complete PAR/consent/code/token chains, private/public clients,
wrong bindings, scope narrowing, absolute expiration, key removal/restoration,
concurrent replay, account changes, insertion rollback and real HTTP token
responses. Bounded cleanup retains used codes and all token hashes until the
family expires, as described below.

## Browser authorization and signup

`pds.oauth.web/handler` accepts only pushed requests at `/oauth/authorize`, consumes
PAR and redirects to `/oauth/flow/<id>`. A separate HttpOnly SameSite=Lax cookie
carries the random browser secret; HTTPS uses the `__Host-` prefix and Secure.
Explicit iframe/fetch starts are rejected. Flow pages and JSON state require the
matching cookie. Only one authorization interaction is active per browser cookie;
starting another replaces that cookie. Secrets never appear in URLs or JSON.

The shared purple Tailwind UI opens account creation for `prompt=create`, including
when a security-session cookie already exists. Users may instead choose an existing
account. Signup checks server registration policy and optional invite requirements;
both did:web and PLC registration use the existing verified provisioning pipeline.
`accounts/register!` deliberately mints no legacy access or refresh JWTs. Browser
registration validates the anonymous session/CSRF first, performs provisioning
without holding browser locks, then rechecks the session and primary credentials.
A pending PLC reservation can outlive the browser flow; users can retry signup or
sign in once provisioning completes.

Password, passkey and additional-factor login use the same `/account` controller.
`interaction/authenticate-browser!` verifies the fully authenticated recent owner
session, account security version, login hint, and both the account and interaction
CSRF tokens. `prompt=login` requires an authentication timestamp at least as recent
as the interaction. Binding rotates the interaction CSRF and cannot switch an
already bound account. Consent is a separate same-origin POST with explicit
Allow/Cancel buttons; no automatic approval is performed.

The consent view shows the exact client metadata URL, requested permissions and
authorized DID. Client names/logos are not treated as verified identities. All
untrusted text uses `textContent`; scripts/styles load only from this PDS, with
no-store, no-referrer and frame-blocking headers. Invalid or expired browser starts
show a static error card without reflecting request contents. The response to a
successful decision contains only the already validated callback location.

Tests cover signup through consent and token exchange, signup/invite policy,
PLC signup without legacy tokens, password/TOTP and passkey binding, both CSRF
proofs, login hints, fresh-login prompts, cookie isolation, denial, epoch changes,
and unsafe authorization starts. Signup/sign-in rendering was also checked in
Chrome. The upstream Node client flow described below covers discovery and the
browser HTTP protocol. Full browser automation and hardware passkeys remain
separate verification work. `/account` provides signup and security settings.

The account-creation prompt follows the UX semantics in
[Initiating User Registration](https://openid.net/specs/openid-connect-prompt-create-1_0.html).
This does not add OpenID Connect or ID tokens to the AT Protocol OAuth profile.

## DPoP resource authentication and transitional permissions

`pds.oauth.resource/wrap` is mounted around the XRPC router. It requires the
`DPoP` authorization scheme for OAuth access tokens; opaque tokens cannot be used
as legacy Bearer credentials. Proofs must match the access-token hash (`ath`),
session key, exact HTTP method and configured public origin plus request path.
Host and forwarding headers cannot override that origin. OAuth responses from this
middleware include a current server nonce and no-store headers; authentication failures use
the DPoP `WWW-Authenticate` challenge. XRPC preflight responses allow Authorization,
DPoP and supported proxy headers, without credentialed cookie CORS.

The middleware validates the stored grant and proof before resolving fresh client
metadata. It then rechecks the grant and commits proof consumption before calling
the endpoint, so failed mutations or insufficient permissions never restore a used
proof. Removing the bound client key or changing its authentication method
permanently revokes the session. Metadata outages fail closed with 503. No database
transaction spans metadata resolution.

Protected endpoints reload the grant in their own transaction, retaining account
and session locks through the mutation. Revocation, expiration or account security
changes between middleware and endpoint execution are rejected. The internal
verified context is bound to the request method/path and cannot be supplied through
HTTP input. OAuth accounts have their own scope field and never gain a legacy
primary-password or privileged app-password identity.

The currently advertised scopes are enforced as follows:

| Scope | Resource behavior |
| --- | --- |
| `atproto` | `getSession`, recommended public DID credentials and missing-blob metadata; no write or proxy authority |
| `transition:generic` | Record writes, blob uploads, and permitted service-auth/proxy calls |
| `transition:email` | Adds email, confirmation and email-factor fields to `getSession` |
| `transition:chat.bsky` | Adds all `chat.bsky.*` service methods, together with `transition:generic` |

Account management, identity changes and repository imports are not granted by
these scopes. Unrecognized protected PDS endpoints require an explicit permission
policy. Service tokens require an explicit method; methodless OAuth delegation
would bypass the separate chat grant. Existing protected service-method restrictions
remain enforced. Proxying rechecks authorization after remote DID resolution and
forwards a newly signed service token, never the OAuth token, DPoP proof or cookies.

PostgreSQL and real HTTP/TLS tests cover nonce retries, wrong key/token/method/URL,
replay after errors, concurrent requests, email filtering, writes, blob upload,
account restrictions, key removal/restoration, metadata outages, lifecycle races,
expiration and service/proxy scope enforcement. Direct granular permissions are
described below, including authenticated permission-set expansion.

## Direct granular permissions

Bluesky [private preference endpoints](PREFERENCES.md) enforce RPC method/audience
permissions. Ordinary preferences are available to authorized OAuth clients;
personal details remain primary-session-only and survive restricted replacements.

PAR accepts these resource permissions in addition to the transitional scopes:

| Scope examples | Granted operation |
| --- | --- |
| `repo:com.example.note?action=create` | Create records in one collection |
| `repo:com.example.note?action=update&action=delete` | Update/delete that collection |
| `repo:*` | Create/update/delete any public record |
| `blob:image/*` | Upload images |
| `blob?accept=image/png&accept=video/mp4` | Upload only the listed media types |
| `rpc:com.example.read?aud=did:web:api.example.com%23appview` | Call one method on one service |
| `rpc:com.example.read?aud=*` | Call that method on any service |
| `rpc:*?aud=did:web:api.example.com%23appview` | Call any permitted service-auth method on that service |
| `account:email` | Read email and verification information |

Named/positional parameters, repeated array values and percent escapes are parsed
strictly. Unknown resources/parameters, scalar duplicates, mixed positional/named
values, partial NSID wildcards and simultaneous RPC method/audience wildcards fail
with `invalid_scope`. Positional `+` remains literal, while query `+` means a space;
use `%2B` in a query MIME subtype such as `application/ld%2Bjson`. MIME matching
normalizes case. Each scope is bounded by 8,192 characters and 64 parameters; the
existing 100-token/8,192-character total scope limit still applies. Requested scope
strings must appear in client metadata and remain within the original grant when
refreshing. Discovery lists the fixed transitional scope names; resource scopes
are parameterized and cannot be exhaustively listed.

Repository authorization runs after the repository lock is held, with the actual
collection and operation. `putRecord` requires `create` for a missing record and
`update` for an existing record. Every `applyWrites` entry is checked, and a denied
entry rolls back the entire batch, including records, blocks, commit and events.
Blob MIME permissions are checked before reading or storing upload bytes. RPC
permissions bind both the exact method and audience, before remote lookup and again
before signing the service token. Explicit granular chat grants can authorize a
specific chat method without the broad transitional DM permission. Existing service
auth method restrictions still apply. Email-read grants do not permit email changes.

Consent and the connected-app list display server-generated descriptions through
DOM `textContent`. Long method, collection and audience identifiers wrap inside the
purple card; the consent fixture was visually checked in Chrome. Authorization
checks use validated scope values, never the displayed descriptions.

`@atproto/oauth-scopes` 0.5.12 is pinned for parser/matcher comparison. Tests also
cover atomic denial, put create/update distinctions, pre-body upload denial,
audience/method substitution and refresh narrowing. The upstream Node OAuth client
completes signup, a scoped record write, refresh and revocation using granular
permissions. Management grants and dynamically resolved permission sets are
described below.

## Included permission sets

Clients may request `include:com.example.authBasic`, optionally with a DID service
audience such as `?aud=did:web:api.example.com%23appview`. As with direct scopes,
the exact requested strings must appear in client metadata. The
[authenticated Lexicon resolver and shared cache](LEXICON-RESOLUTION.md) establish
DNS authority, verify the publisher's DID and signed repository record, then
expand only the set's permitted namespace. Blob, account and identity authority
must still be requested directly. Unknown or invalid declarations are ignored
whole, never converted into broader permissions.

PAR resolves the requested schemas after client/DPoP authentication and before
opening the request-storage transaction. Migration 034 persists the verified
schema versions alongside the pushed request. Browser interaction, consent and
authorization-code snapshots retain those versions, even if the publisher or
shared cache changes before code exchange. Clients cannot submit these snapshots.
Missing uncached sets reject a new authorization with temporary unavailability.

Consent groups sets by their localized title and namespace, shows their details,
and lets the user expand the exact permissions. Language selection uses the
browser's preferences with parent-language and default-text fallback. All content
is inserted as text. The screen explains that sets can evolve within their
namespaces when the app refreshes its session. Connected-app management shows the
session's latest resolved sets with the same expandable presentation.

Each access token stores an immutable array of effective direct permissions.
Resource requests use that array without fetching Lexicons or re-expanding a live
set. Initial issuance uses the approved schema snapshot. Refresh resolves requested
sets outside account/session locks, then rechecks current grant state and atomically
rotates tokens and updates session snapshots. Earlier access tokens retain their
original authority until expiry or session revocation. Original scope strings stay
fixed, including an inherited audience; narrowing a refresh cannot substitute a
new set or audience. Existing pre-migration direct-scope tokens remain valid.

A failed remote refresh can use the session's previous verified schema even after
cache expiry/eviction. Other resolution failures leave a valid refresh token
unconsumed. Account invalidation, revocation and refresh replay are rechecked before
any issuance; replay still revokes the family when schema resolution fails.

Requests allow at most 16 distinct sets and 4 MiB of serialized schema snapshots.
The resolver checks a 30-second overall budget between and after bounded individual
lookups. Expanded token permissions are limited to 10,000 scopes and 1,000,000
characters. Requests exceeding these limits fail without issuing partial grants.

Integration tests cover consent/code freezing, publisher changes between issuance
and refresh, unchanged older access tokens, narrowing and audience isolation,
forbidden cross-namespace/account/blob grants, expired-cache fallback, revocation
during resolution, failure rollback and legacy token compatibility. The pinned
upstream Node OAuth client also completes signup, a set-authorized write, refresh
and revocation through the mounted HTTP endpoints. Chrome visual checks cover
localized titles, literal markup-like text and long expanded permission wrapping.

## Account and identity management permissions

| Scope | Authorized operations |
| --- | --- |
| `account:email?action=manage` | Read email, request/consume confirmation tokens, request/complete email changes |
| `account:repo?action=manage` | Import a complete signed repository CAR |
| `account:repo` | No additional authority; repository information is already public |
| `identity:handle` | Change the account handle and its DID-document alias |
| `identity:*` | Change the handle, request PLC signing approval, sign and submit PLC operations |

These grants satisfy the specific operation's permission check without turning an
OAuth account into a primary-password session. They do not authorize app-password
creation, factor enrollment/removal, account deletion or status changes. Legacy
primary/app-password requirements remain unchanged. Unspecified account actions
default to `read`; `manage` includes read access. Identity wildcard includes handle
permission. Consent and connected-app descriptions identify the wider authority.

Email confirmation and email changes retain the existing address-bound, one-use
email challenges. A confirmed current address still requires its update token.
OAuth `updateEmail` rejects explicit `emailAuthFactor` fields; clients cannot use
that field to configure a factor. Changing the address clears the old address's
email factor as in the existing account lifecycle. Confirmation/security changes
advance the account epoch and invalidate existing OAuth grants, including the
calling grant. OAuth email updates revoke all legacy sessions; the opaque OAuth
session ID is never used as a legacy UUID. The new address receives a confirmation
message through the configured email outbox/Worker.

Import permission is distinct from collection write permissions. Import validates
the signed CAR, DID, block ownership and repository state, then rechecks the OAuth
grant before committing. Revocation during verification prevents any mutation.
Handle updates retain domain-control verification and durable PLC reconciliation.
Full PLC control still requires a managed rotation key, a current verified audit,
and a one-use email token before signing. Submission retains signature and local
credential constraints. Operations that release locks for external work reload
authentication and permissions before persisting the result.

Active OAuth accounts may read recommended public DID credentials and missing-blob
metadata with `atproto` alone, matching the reference endpoints.

Deactivated migration accounts can authorize and use their own OAuth sessions.
Because every status change advances the account's OAuth epoch, sessions issued
while active end at deactivation, and sessions authorized while deactivated end
at reactivation with the ordinary `account_changed` revocation. A deactivated
account can complete a fresh authorization flow (sign-in, consent, code exchange
and refresh all bind the deactivated epoch); the resulting grant works only on
endpoints that accept inactive sessions, such as `getSession`, `uploadBlob`,
`importRepo` and `listMissingBlobs`, subject to the grant's permissions. Other
routes reject the session with `401 InvalidToken`, and `checkAccountStatus`
remains a 403 permission denial until status permissions gain published
semantics. Taken-down accounts can neither authorize nor use OAuth sessions.

Tests cover read/manage isolation, email proof and epoch invalidation, legacy
session revocation, forbidden factor/account actions, signed imports and mid-import
revocation, handle-only isolation, and PLC handle/sign/submit flows against a local
TLS directory. Scope parser/matcher comparisons include the management scopes.

## Revocation and owner session management

`tokens/revocation-handler` implements the mounted `/oauth/revoke` adapter using
bounded POST form bodies, public-client CORS, no-store responses and DPoP nonce
headers. It accepts both access and refresh tokens and ignores `token_type_hint`.
Revoking either kind revokes the entire token family, including rotated tokens.
Previously used refresh tokens can still identify their family for logout.
Unknown tokens and repeated valid revocations return HTTP 200 with an empty body;
missing/empty token parameters are invalid requests. GET and query-string
credentials are rejected.

Revocation requires fresh client metadata, the original client authentication
method/key and the session's DPoP key. DPoP covers POST to the configured revocation
URL; as an authorization-server form request it does not require an `ath` claim.
Client assertion and proof replay state commit before the revocation transaction.
A request with another client's credentials or another session key cannot revoke
the grant. Authoritative removal of a confidential client key permanently revokes
its session, as it does on token/resource requests. Expired or deactivated sessions
remain revocable. Account-then-session locking serializes revocation with refresh
and protected resource mutations.

The `/account` security screen lists and disconnects the owner's active sessions.
Only a recent complete browser login may access the list or revoke a session;
same-origin POST and CSRF validation apply to management actions. Lists expose only
the session identifier, client metadata URL, original scope, creation and expiration
times, plus readable permission descriptions. They never include token values/hashes, PKCE data or DPoP/client keys.
Invalidated, expired and revoked sessions are omitted. Migration 032 indexes stable
session-ID pagination; each page has at most 20 entries. Foreign/unknown IDs produce
the same idempotent result, without modifying another account's sessions.

Tests cover both token types, rotated credentials, unknown/expired tokens, client
and key binding, replay, concurrent refresh/revoke, owner isolation, pagination,
factor/CSRF/Origin checks and the real HTTP contract. The connected-app screen was
visually checked in Chrome with fixture data. Protocol behavior follows
[RFC 7009](https://www.rfc-editor.org/rfc/rfc7009.html), with mandatory AT Protocol
client and DPoP binding.

## Discovery and route integration

A database-backed `pds.app/handler` publishes:

| Route | Methods | Purpose |
| --- | --- | --- |
| `/.well-known/oauth-authorization-server` | GET, HEAD | Issuer, endpoints, PKCE, client authentication and DPoP capabilities |
| `/.well-known/oauth-protected-resource` | GET, HEAD | PDS resource and authorization-server discovery |
| `/oauth/par` | POST | Push the authorization request |
| `/oauth/authorize` | GET | Consume PAR and enter the browser flow |
| `/oauth/flow/:id` | GET | Sign in, create an account and give explicit consent |
| `/oauth/token` | POST | Exchange a code or rotate a refresh token |
| `/oauth/revoke` | POST | Revoke the bound session family |

Metadata and protocol endpoints support OPTIONS and public-client CORS. The outer
header middleware also covers rate-limit 429 and backend-unavailable 503 responses.
DPoP requests receive a current nonce, and XRPC 401 challenges advertise protected
resource metadata. Cookie-based browser routes retain their same-origin policy.
Discovery uses only `PDS_PUBLIC_URL`, never incoming Host or forwarding headers.
The configured origin must have a lowercase hostname, no path or explicit default
port, and HTTPS; loopback HTTP is supported for development. Settings parsing
removes an optional trailing slash before the origin check. The database-free
health/banner handler does not publish OAuth routes.

`@atproto/oauth-client-node` 0.5.7 is pinned in `scripts/conformance`. With
`PDS_TEST_UPSTREAM=true`, PostgreSQL integration tests exercise public-client
`prompt=create` signup and confidential-client password login through the mounted
HTTP server. The SDK performs discovery, PAR with PKCE and nonce retry, client
assertions, code exchange, DID/PDS verification, DPoP resource requests, refresh
and revocation. Tests assert a persisted record, revocation in PostgreSQL, email
scope filtering and absence of legacy tokens on signup. The browser HTTP controller
uses the actual cookie, Origin and CSRF requirements and explicit consent.

The fixture maps allowlisted HTTPS origins to a local HTTP listener and serves
client metadata through the test resolver. It preserves the advertised issuer and
signed proof targets; it does not bypass SDK or PDS cryptographic verification.
This establishes local reference-client interoperability, not deployed DNS/TLS,
a real browser ceremony or external AppView/relay compatibility.

## Expired-grant cleanup

The main process runs `pds.oauth.cleanup/collect!` once per minute. Each sweep
locks at most 50 expired families and removes at most 1,000 token rows in total.
It only removes a family after all its tokens have been collected; large refresh
histories take several sweeps rather than triggering an unbounded cascading delete.
A separate transaction removes at most 1,000 expired codes with no remaining
family. Existing expiry/session indexes support the selections.

Every token hash and its used code remain until the family's absolute expiry,
including rotated, revoked and account-invalidated grants. This preserves replay
revocation and lets an older token identify its family for logout. Expired grants
may cease to be identifiable after collection; authenticated revocation of an
unknown token still returns the required idempotent success response.

`FOR UPDATE SKIP LOCKED` permits concurrent PDS workers and skips active family,
token and code operations. Family collection takes session then token locks;
code collection runs only after releasing them, preserving the code-then-session
order of exchange/replay. SQL statements have a five-second deadline, failures
roll back their batch, and a later sweep resumes orphan cleanup independently.
Shutdown interrupts the worker between batches and closes its executor. Other
short-lived OAuth proof, PAR and interaction ledgers retain their existing
bounded cleanup on use.

PostgreSQL tests cover batch limits, large families, expired-code/refresh replay
retention, locked rows, concurrent refresh and collectors, rollback and resumption.
The process lifecycle test covers starting and stopping the registered worker.

## Remaining steps

1. Full browser/hardware ceremonies and deployed reference-client verification.
   The published permission specification currently defines `email` and `repo`
   account attributes; status permissions remain unsupported until their
   semantics are defined.

Sources: [AT Protocol OAuth profile](https://atproto.com/specs/oauth),
[RFC 9449 DPoP](https://www.rfc-editor.org/rfc/rfc9449.html),
[RFC 7638 JWK thumbprints](https://www.rfc-editor.org/rfc/rfc7638.html), and
[RFC 3986 URI normalization](https://www.rfc-editor.org/rfc/rfc3986.html).
Consulted 2026-09-27.
