# Authenticated Lexicon resolution

`pds.lexicon-resolver/resolve!` resolves a published schema through its exact
namespace's DNS TXT record, publisher DID document, repository signing key and
PDS. It requests a partial CAR from `com.atproto.sync.getRecord` and verifies the
commit signature and record inclusion before returning the schema and provenance
(`did`, record `cid`, commit `head` and `rev`).

For `com.example.authBasic`, the only DNS name queried is
`_lexicon.example.com.`. Resolution never searches parent namespaces or falls back
to an unsigned JSON record. Duplicate identical DID records are accepted;
conflicting, missing or unsupported authorities fail. Schema `$type`, version,
NSID and definition structure must match the requested record.

The resolver uses the application's guarded HTTPS client: public destination
checks, TLS hostname verification, no ambient credentials, no redirects on the
CAR request, no compressed responses, a five-second CAR fetch timeout, and a
2 MiB response ceiling. Sixteen concurrent resolutions are admitted per resolver;
identity/DNS lookups retain their own existing bounds. No database transaction
spans resolution. Callers receive redacted failure categories rather than remote
payloads or network exception details.

`pds.protocol.repository/verify-record` verifies inclusion or absence at an exact
collection/key against a trusted DID and signing key. It checks the signed commit,
CID hashes, visited MST node structure, ordering, subtree bounds and prefix
compression. Missing path blocks fail; unrelated blocks cannot establish inclusion.
Record links are not traversed. This is a partial proof: it does not certify the
canonical structure of unvisited branches or prove that a signed commit is the
publisher's latest revision.

Tests cover both supported signing curves, partial inclusion/absence proofs from
the pinned upstream `@atproto/repo` implementation, missing/corrupt/disconnected
blocks, malformed signed trees, conflicting DNS authority, wrong identities/keys,
invalid schemas, concurrency exhaustion, and real TLS fetch/redirect/encoding/size
failures. Existing complete repository import verification shares the same commit
validation and retains its complete-tree canonicality checks.

## Permission-set expansion and cache

`pds.oauth.permission-sets/expand` interprets an authenticated schema at an
`include:` invocation. Only repository and RPC declarations can grant authority.
Every collection/method in a declaration must belong to the set's NSID group or
one of its child groups. Wildcards, parent/sibling namespaces, unknown resources,
unknown fields and invalid parameter values cause the entire declaration to be
ignored. Other valid declarations in the set continue to work. Sets cannot grant
blob, account or identity permissions or recursively include other sets.

RPC declarations may name the wildcard audience or inherit a DID service audience
from their invoking `include:`. Explicit DID audiences inside a set, conflicting
audience/inheritance fields and missing inherited audiences grant nothing.
Expansion returns ordinary direct scope strings; these will be persisted as
immutable access-token permissions. Titles, details and language maps are retained
for consent presentation and never participate in authorization decisions.

The shared PostgreSQL cache (`pds.oauth.permission-cache`, migration 033) stores
verified schema bytes and DID/CID/commit/revision provenance. Schemas become stale
after 24 hours and expire for new sessions after 90 days. Stale entries trigger a
refresh. Failed resolution can reuse an unexpired schema, with a 60-second retry
cooldown; it does not extend the original successful fetch time. Existing sessions
may supply their own previously authenticated schema snapshot as fallback beyond
cache expiry or eviction. A fallback never seeds the cache for new sessions.

Thirty-second leases coordinate refresh across processes. DNS/HTTPS verification
runs outside database transactions. A superseded worker cannot publish or return
its obsolete result. A cold in-progress lookup returns temporary unavailability;
stale lookups can continue using their eligible cached entry. Same-publisher
revision regressions and conflicting heads at the same revision are rejected.
DNS-authorized publisher DID changes may start a different revision sequence.

The cache holds at most 1,024 entries, each capped at 1,000,000 encoded bytes.
Admission evicts at most one eligible stale entry, never an active lease or a
successful entry less than 30 minutes old. If no slot is available, new lookups
fail temporarily; an existing session can still use its authenticated fallback.
Set envelopes allow up to 1,000 permission declarations, 100 translations per
text field, and 10,000 expanded scopes totaling at most 1,000,000 characters.

Tests compare expansion with the pinned upstream scope library and exercise
namespace escapes, malformed declarations, audience inheritance, shared cache
reopening, simultaneous refreshes, expired leases, revision rollback, cache
capacity, signed-schema persistence and expiry during a failed network request.

OAuth PAR accepts `include:` and freezes the resolved schemas for consent and
initial issuance. Access tokens store immutable effective permissions; refresh
can update the session's schema snapshots. Consent and connected-app management
show localized set summaries and expandable details. See [OAuth integration](OAUTH.md#included-permission-sets).
## Record-schema admission

`pds.lexicon-schema/record-catalog!` prepares a closed graph of record definitions
before remote schemas can be used as validator instructions. Its lookup dependency
must return authenticated documents. Each NSID is looked up once per graph, including
recursive references. Only reachable definitions enter the resulting catalog;
unrelated endpoint or future-type definitions do not prevent record validation.

The compiler checks constraint types and bounds, record keys, required/nullable
properties, supported formats, local and cross-document references, and union
target types. Unknown instructions in reachable definitions are rejected rather
than silently ignored. References to records must retain the referenced record's
`$type` during value validation. Recursive containers are allowed; validation of
actual data retains its depth limit.

Compilation is bounded to 32 documents, 1,000,000 encoded bytes per document,
4 MiB total, 10,000 schema nodes and depth 64. It returns no partial catalog on
resolution or admission failure. Tests cover all 17 bundled record schemas,
the upstream record fixture, cross-document recursion, invalid instructions,
missing references, union target confusion, nested record tags and graph bounds.

## Dynamic record writes

`createRecord`, `putRecord` and `applyWrites` now use authenticated resolution for
explicit `validate=true` writes whose collection is outside the bundled catalog.
All reachable schema dependencies are authenticated under their own NSID authority.
Bundled schemas remain pinned, including when referenced by a dynamic schema.

Validation modes retain their protocol semantics:

- `validate=true` requires a complete admitted schema graph. Resolution failures,
  unsupported instructions or capacity exhaustion return `InvalidRecord` without
  changing the repository. Successful validation returns `validationStatus: valid`.
- Omitted `validate` uses bundled schemas or an unexpired cached graph. It does not
  start network lookups. Unknown schemas remain writable with a validation status
  of `unknown`; known schemas still reject invalid values.
- `validate=false` skips schema lookup and validation, and omits validation status.
  Data-model validation, collection `$type`, blob ownership and size limits remain.
  Deletes never resolve schemas.

Requests authenticate, check repository ownership, collection/action permissions
and generic record data before any schema lookup. Resolution holds no database
transaction. The write transaction then rechecks authentication, ownership,
current create/update permissions and swap conditions. Credential revocation or a
concurrent change from create to update cannot bypass authorization. All batch
entries use fixed per-collection schema graphs and commit or roll back together.

The process-local cache holds at most 128 graphs and 16 MiB of encoded graph data.
Successful graphs expire after one hour; failed lookups have a one-minute retry
cooldown. Sixteen requests may resolve simultaneously, with one in-flight lookup
per collection. Competing cold lookups fail explicit validation promptly. A request
checks a 30-second budget before and after each bounded remote lookup. There is no
unbounded queue, stale fallback or partial-graph acceptance. Cache contents are
discarded on restart; record validation does not reuse OAuth permission-set cache
policy or grant snapshots.

Tests exercise real HTTP record writes with signed root/dependency proofs, cached
optimistic validation, skip mode, malformed-proof rejection, retry cooldown,
concurrency and cache bounds, full batch rollback, account locks remaining free
during lookup, legacy/OAuth revocation and a concurrent put-action change.

Sources: [Lexicon publication and resolution](https://atproto.com/specs/lexicon#lexicon-publication-and-resolution),
[permission sets](https://atproto.com/specs/permission#permission-sets).
