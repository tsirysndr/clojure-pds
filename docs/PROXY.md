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
| `PDS_PROXY_MAX_CONCURRENT` | `16` | Preparation and response-delivery slots per handler; maximum 256 |
| `PDS_PROXY_MAX_REQUEST_BYTES` | `5242880` | Disk-staged POST body limit; maximum 64 MiB |
| `PDS_PROXY_MAX_RESPONSE_BYTES` | `10485760` | Disk-staged upstream response limit; maximum 64 MiB |
| `PDS_PROXY_TIMEOUT_MS` | `10000` | Upstream exchange deadline; maximum 60,000 ms |
| `PDS_PROXY_ACCOUNT_RATE_LIMIT_ENABLED` | `true` | `false` disables the per-account proxy budget |
| `PDS_PROXY_ACCOUNT_RATE_LIMIT_REQUESTS` | `600` | Proxy requests per account per window; 1–1,000,000 |
| `PDS_PROXY_ACCOUNT_RATE_LIMIT_WINDOW_SECONDS` | `300` | Per-account budget window; 1–86,400 s |

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
both phases, staging the bounded request body, response staging, and downstream
HTTP delivery. The normal server creates one proxy handler.

## Authentication and forwarding

The proxy authenticates before staging the body or resolving the destination,
then authenticates again after DID resolution and before creating the upstream
token. Database transactions do not span network operations. Account status,
session revocation, and ordinary versus privileged app-password permissions are
checked by the same policy used by `getServiceAuth`. Protected account methods
cannot use service authentication; privileged chat methods require a primary or
privileged app-password session. OAuth callers must prove their DPoP-bound token
and hold the required RPC method/audience permission (or an applicable transitional
scope). DPoP is verified locally; only the replacement service JWT goes upstream.

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
4xx/5xx status and bounded error/message when the complete error envelope is at
most 64 KiB. Larger, malformed or HTML errors become a generic `UpstreamFailure`. Network, TLS, oversize response and timeout failures produce
502, full proxy capacity produces 503, and the configured memory/Redis request
limiter also applies. Each authenticated account additionally has its own proxy
budget (600 requests per 5 minutes by default), charged after authentication and
before request staging, DID resolution or the upstream exchange; exceeding it
produces `429 RateLimitExceeded` with `Retry-After`, and an unavailable shared
limiter fails closed with 503. The budget uses the configured rate-limit backend,
so Redis deployments share it across instances. Oversize request bodies produce 413. Temporary-file failures
produce a sanitized `503 ProxyUnavailable`.

## Response staging and resource ownership

Upstream response bytes are copied in chunks of at most 64 KiB into a private
mode-0600 file opened with `DELETE_ON_CLOSE`. The client uses Jetty's
[InputStream response listener](https://javadoc.jetty.org/jetty-12.1/org/eclipse/jetty/client/InputStreamResponseListener.html),
which applies backpressure while the consumer writes the file. The total limit
applies to declared lengths and actual bytes, including chunked responses. The
exchange must finish successfully before any response is published: truncated
bodies, size violations, unsupported encoding, and timeouts produce a complete
XRPC error instead of a partial upstream success response. Failed exchanges are
aborted and their listener is closed. The output file is never used as a cache.

Successful bodies use the owned HTTP stream transport with known actual length,
64 KiB writes and downstream backpressure. Network and database resources have
already been released before delivery. The proxy permit remains held until that
body closes, including normal completion, client disconnect, write failure or
server shutdown. HEAD and 204 responses close staging resources immediately;
HEAD preserves the upstream representation length without fetching the body.
Small error envelopes are parsed separately, with a 64 KiB memory bound, then
staging resources are closed. Direct handler callers must close owned bodies.

POST request bodies are copied into their own private mode-0600 `DELETE_ON_CLOSE`
file in 64 KiB chunks before any remote work; oversize bodies (fixed-length or
chunked) produce 413 without contacting the upstream, and the staged bytes are
sent exactly once with their known Content-Length. The request file closes as
soon as the exchange finishes, before response delivery begins.

Budget `PDS_PROXY_MAX_CONCURRENT × (PDS_PROXY_MAX_REQUEST_BYTES +
PDS_PROXY_MAX_RESPONSE_BYTES)` for temporary proxy payload: **240 MiB by
default**, separately from repository and blob staging budgets, plus filesystem
overhead. No request or response payload is held on the JVM heap beyond one
64 KiB copy buffer per phase. The upstream deadline covers response consumption
as well as header arrival; the downstream transport has its own write deadline.

## Verification and remaining work

Real HTTP and local TLS integration tests cover scoped signatures (also verified
by the pinned upstream JWT library), byte/query/header forwarding, defaults,
local route precedence, app-password privileges, inactive accounts, concurrent
limits, revocation during resolution, private destinations, hostname mismatches,
timeouts, rate limits, redirects, interrupted responses and malformed errors.

Response tests additionally cover multi-megabyte chunked delivery without the
buffered exchange helper, permits held by prepared bodies, no retained database
connection, disk failures, HTTP disconnect cleanup, HEAD/204 behavior, and timeout
after headers and a partial body. Request tests cover megabyte fixed-length and
chunked HTTP uploads with a known upstream Content-Length, oversize rejection
before remote work, sanitized staging failures and request/response file cleanup.
Local OAuth tests cover exact method/audience permissions and credential
replacement. Budget tests cover per-account 429 responses with Retry-After
before any remote work, independent accounts, uncharged local routes and a
failed shared limiter failing closed.

Interoperability
with deployed AppViews/labelers remains on the full PDS roadmap. DPoP passthrough
and WebSocket proxying are explicitly rejected. Tests use isolated local services;
no external account or service has been contacted to establish interoperability.

Policy reference: [pinned reference PDS proxy implementation](https://github.com/bluesky-social/atproto/blob/7a857989751ae31518509d69ab7194a922064f3d/packages/pds/src/pipethrough.ts).
