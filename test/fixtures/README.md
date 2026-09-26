# Upstream conformance fixtures

Source: https://github.com/bluesky-social/atproto-interop-tests

Pinned revision: `056e5741bb330757205d6b16db5266fffcae937b`

Unmodified fixtures under CC0; see LICENSE-CC0. Tests consume the syntax, data-model,
crypto, and MST subsets as those implementations land.

Known upstream discrepancy: the `com.middle...foo` valid NSID fixture exceeds the
current specification's 253-character domain-authority limit. The test preserves
that fixture but expects rejection, following https://atproto.com/specs/nsid.
