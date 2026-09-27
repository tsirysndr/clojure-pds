# Authenticated service proxy

Unknown `/xrpc/<NSID>` routes can be forwarded to a DID service using the
`atproto-proxy: did:web:service.example.com#service` request header. The caller
must supply a current local access token for an active account. Existing local
routes take precedence, including their method and authentication requirements.
The two [private preference endpoints](PREFERENCES.md) are an explicit exception:
an `atproto-proxy` audience different from their local audience selects the proxy.
The proxy supports GET, HEAD and POST. Invalid NSIDs and requests without a
selected service retain the ordinary `MethodNotImplemented` response.

## Configuration

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `PDS_APPVIEW_SERVICE` | Unset | DID with service fragment for unimplemented XRPC methods |
| `PDS_LABELER_SERVICE` | Unset | DID with service fragment for `com.atproto.moderation.createReport` and `tools.ozone.*` |
| `PDS_PROXY_MAX_CONCURRENT` | `16` | Simultaneous requests per process; maximum 256 |
| `PDS_PROXY_MAX_REQUEST_BYTES` | `5242880` | Buffered POST body limit; maximum 64 MiB |
| `PDS_PROXY_MAX_RESPONSE_BYTES` | `10485760` | Buffered upstream response limit; maximum 64 MiB |
| `PDS_PROXY_TIMEOUT_MS` | `10000` | Upstream exchange deadline; maximum 60,000 ms |

All numeric settings must be positive integers. Explicit `atproto-proxy` overrides
the configured default. Labeler methods without a labeler default require an
explicit header; they do not silently fall back to the AppView. There are no
hardcoded public AppView or labeler destinations.

The DID resolver supports `did:plc` and hostname-based `did:web`. The selected
service must have a unique matching full or fragment-only identifier, a nonempty
service type, and an HTTPS origin as its endpoint. Credentials, URL paths, queries
and fragments are rejected. XRPC paths are top-level paths in the protocol.
Resolution is fresh and uses the existing independent identity limits. The
exchange timeout begins after resolution; the proxy concurrency permit covers
both phases and reading the bounded request body.

## Authentication and forwarding

The proxy authenticates before reading the body or resolving the destination,
then authenticates again after DID resolution and before creating the upstream
token. Database transactions do not span network operations. Account status,
session revocation, and ordinary versus privileged app-password permissions are
checked by the same policy used by `getServiceAuth`. Protected account methods
cannot use service authentication; privileged chat methods require a primary or
privileged app-password session.

Each exchange gets a fresh repository-key JWT with the account DID as `iss`,
the complete DID-and-service reference as `aud`, the exact NSID as `lxm`, a random
`jti`, `kid=#atproto`, and a 60-second lifetime. Full service audiences follow the
[current XRPC specification](https://atproto.com/specs/xrpc); older services that
accept only a bare-DID audience require an upstream update. Local session tokens
and client cookies are never sent to the service.

Raw query strings retain ordering, repeated parameters, and percent encoding.
POST bytes and Content-Type are preserved. Forwarded request headers are limited
to Accept, Accept-Language, Content-Type, Atproto-Accept-Labelers, X-Bsky-Topics and
X-Atproto-*; Connection-nominated headers are removed. Authorization is replaced,
and Accept-Encoding is set to identity. Host is derived from the service URL.

The shared HTTPS client validates every resolved socket address and verifies the
original hostname's TLS certificate. Private or mixed public/private DNS answers
are rejected. No redirects, cookies, automatic mutation retries, or decompression
are used. Both encoded request bodies and encoded upstream responses are rejected.
A network failure is ambiguous for a mutation: the service may already have
processed it. The PDS does not retry it.

Successful responses retain their status, bounded raw bytes, MIME type and selected
content/AT Protocol headers. HEAD preserves the upstream Content-Length. Responses
are marked no-store and carry restrictive browser content headers. Set-Cookie,
Location and hop-by-hop headers are not relayed. Valid XRPC errors retain their
4xx/5xx status and bounded error/message; malformed or HTML errors become a generic
`UpstreamFailure`. Network, TLS, oversize response and timeout failures produce
502, full proxy capacity produces 503, and the configured memory/Redis request
limiter also applies. Oversize request bodies produce 413.

## Verification and remaining work

Real HTTP and local TLS integration tests cover scoped signatures (also verified
by the pinned upstream JWT library), byte/query/header forwarding, defaults,
local route precedence, app-password privileges, inactive accounts, concurrent
limits, revocation during resolution, private destinations, hostname mismatches,
timeouts, rate limits, redirects, interrupted responses and malformed errors.

The implementation currently buffers transfers. Streaming, OAuth authorization
and permission scopes, service-specific account abuse budgets, and interoperability
with deployed AppViews/labelers remain on the full PDS roadmap. DPoP passthrough
and WebSocket proxying are explicitly rejected. Tests use isolated local services;
no external account or service has been contacted to establish interoperability.

Policy reference: [pinned reference PDS proxy implementation](https://github.com/bluesky-social/atproto/blob/7a857989751ae31518509d69ab7194a922064f3d/packages/pds/src/pipethrough.ts).
