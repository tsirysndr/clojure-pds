# Upstream conformance fixtures

Source: https://github.com/bluesky-social/atproto-interop-tests

Pinned revision: `056e5741bb330757205d6b16db5266fffcae937b`

Unmodified fixtures under CC0; see LICENSE-CC0. Tests consume the syntax, data-model,
crypto, and MST subsets as those implementations land.

Lexicon tests also consume the record catalog/data and datetime, URI and language
syntax/parse fixtures. The Lexicon `full` record fixture uses DAG-CBOR CIDs for its
blobs; the current data-model spec requires raw CIDs. We assert rejection of the
original by the data-model validator, then substitute raw blob CIDs solely for
schema-level tests. Invalid-record cases receive the required `integer` field
(except the missing-field case), so that it cannot mask the intended failure.

The Lexicon `cid` string format currently enforces the same blessed CID formats
as data-model links; the older, expansive `cid_syntax_valid.txt` fixtures are not
used. General multibase/IPFS CIDs outside the AT Protocol blessed set are rejected.

Known upstream discrepancy: the `com.middle...foo` valid NSID fixture exceeds the
current specification's 253-character domain-authority limit. The test preserves
that fixture but expects rejection, following https://atproto.com/specs/nsid.
