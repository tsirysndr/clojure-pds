# Identity caching and freshness

The public `resolveHandle`, `resolveDid` and `resolveIdentity` endpoints cache
successful remote handle bindings and DID documents. Cache scope is one handler
instance, normally one PDS process. There is no persistent or shared Redis cache;
restarting the process starts cold. The configured PLC directory and network
dependencies belong to that resolver instance, so entries cannot cross directory
or resolver configurations.

| Variable | Default | Range |
| --- | --- | --- |
| `PDS_IDENTITY_CACHE_TTL_SECONDS` | `300` | 0–3,600 seconds; 0 disables caching |
| `PDS_IDENTITY_CACHE_MAX_ENTRIES` | `1024` | 1–10,000 cached and in-flight entries combined |

Expiration uses a monotonic clock and starts after a successful lookup. The cache
also caps retained value payloads at 16 MiB of encoded JSON; entry/key/object
overhead is additional and bounded by the entry count and identifier limits.
Oversized values may be returned but are not retained. Expired entries are
discarded on lookup, and least-recently-used completed entries are evicted to make
room. In-flight entries are not evicted to admit another miss: a fully busy cache
returns 503. Configuration changes require a restart.

Concurrent ordinary misses for the same key share one result. Followers wait at
most 15 seconds without starting duplicate work. Public identity routes retain
their existing 32-request concurrency limit and general IP rate limit. DNS,
public-address enforcement, TLS validation, body limits, deadlines and PLC audit
verification are unchanged. Only successful values are retained; failures are
not cached and expired values are not served after lookup errors.

Handle cache keys are lowercase; DIDs remain case-sensitive. `resolveIdentity`
performs the bidirectional check using the currently available handle and DID
values on every call; it does not cache a separate pre-approved identity result.
A missing, mismatched or failed handle binding yields `handle.invalid` while a
valid DID document remains usable. As with any TTL cache, remote changes can be
invisible to ordinary public reads until expiration or explicit refresh.

## Explicit refresh

`com.atproto.identity.refreshIdentity` validates its identifier, removes the
known cached DID and related handle bindings, then forces fresh remote lookup
for each binding used by the bidirectional check. A small request-local memo
avoids fetching the same handle twice during that refresh. Supplying a handle
also invalidates its previously cached DID, so moving a handle to another DID
does not leave the old mapping active through this path.

Invalidated in-flight lookups lose permission to populate that entry. Waiters
are released with a retryable 503; late results cannot replace a newer refreshed
value. Refresh failures do not restore the old cached document. Other completed
identities remain cached. Refresh applies to the instance that handles the
request; multi-instance deployments rely on TTL expiry or must refresh each
instance explicitly.

## Resolution that remains fresh

Hosted handles, hosted non-imported did:web documents and the service DID are
checked in PostgreSQL before the remote cache on every request. Deletion,
provisioning status and local handle changes therefore take effect immediately.
Portable PLC identities and imported identities still resolve remotely, including
when they have local account rows; a local snapshot is never authoritative for
those identities.

Only the public identity route resolver receives this cache. Internal resolvers
for service-token authentication, migration/activation, account credential
validation, custom-handle verification, service proxying and authenticated Lexicon
resolution do not receive it. Their identity lookups stay fresh; schema and OAuth
permission-set caches retain their own separately documented policies. A public
cached signing key cannot authorize a migration token after the source rotates
its key.

Unit tests cover TTL boundaries, disabling, LRU and payload limits, concurrent
misses, refresh fencing and failed lookups. HTTP/PostgreSQL tests cover cache
reuse, forced refresh, disabling, immediate hosted deletion and rejection of an
old service-token signature despite a public cached key. A real TLS PLC-directory
fixture verifies refresh and subsequent reads reject invalid audits and observe
tombstones. External directory outages/rollovers in deployment remain unverified.

Identity verification follows the [DID](https://atproto.com/specs/did) and
[handle](https://atproto.com/specs/handle) specifications; TTL and capacity values
are this server's operational policy.
