# Trusted Lexicon catalog

Unmodified schemas from `bluesky-social/atproto`, revision
`7a857989751ae31518509d69ab7194a922064f3d`. Upstream's MIT/Apache-2.0 license
notices are included here. `index.json` records each file's SHA-256 checksum.

The 17 record roots cover profiles, statuses, posts, likes, reposts, feed
generators, thread/post gates, follows, blocks, lists and membership, starter
packs, verifications, labeler declarations and chat declarations. All referenced
schemas are included. The catalog also covers all 58 implemented XRPC endpoints
(queries, procedures and the repository subscription), with their input/output
definitions and references: 95 files total. `endpointRoots` in `index.json`
enumerates that coverage. This is a curated catalog, not dynamic
Lexicon resolution or a claim that every network Lexicon is supported.

JSON request and query-parameter readers validate against these pinned schemas.
Query strings are decoded as typed values for validation; handlers continue to
receive their established wire representation. Repeated parameters are arrays
only when declared. Defaults are applied to declared endpoint fields, while
unknown record bodies and future extension fields remain unchanged. Validation
runs where handlers read input, preserving their authentication order. Binary
uploads and subscription frames retain their separate bounded protocol readers.
The Node conformance suite compares input acceptance with `@atproto/lexicon`
0.7.14. The integration runner also observes actual PDS output, checking successful
responses for every implemented endpoint, all five repository stream message types
and error frames against the local and pinned upstream validators. The suite fails
if any endpoint or message type lacks successful coverage. This verifies produced
responses and framing, not all possible values or external client/relay behavior.

Refresh deliberately with `python3 scripts/vendor-lexicons.py` after reviewing
its pinned revision and roots. No schema downloads occur at server runtime.
