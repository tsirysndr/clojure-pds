# Trusted Lexicon catalog

Unmodified schemas from `bluesky-social/atproto`, revision
`7a857989751ae31518509d69ab7194a922064f3d`. Upstream's MIT/Apache-2.0 license
notices are included here. `index.json` records each file's SHA-256 checksum.

The 17 record roots cover profiles, statuses, posts, likes, reposts, feed
generators, thread/post gates, follows, blocks, lists and membership, starter
packs, verifications, labeler declarations and chat declarations. All referenced
schemas are included (33 files total). This is a curated catalog, not dynamic
Lexicon resolution or a claim that every network Lexicon is supported.

Refresh deliberately with `python3 scripts/vendor-lexicons.py` after reviewing
its pinned revision and roots. No schema downloads occur at server runtime.
