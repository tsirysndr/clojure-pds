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

This is the resolution foundation. OAuth `include:` support still requires cache
policy, namespace-constrained permission expansion, immutable access-token
snapshots, refresh integration and consent presentation. Dynamic record validation
is not yet connected to this resolver.

Sources: [Lexicon publication and resolution](https://atproto.com/specs/lexicon#lexicon-publication-and-resolution),
[permission sets](https://atproto.com/specs/permission#permission-sets).
