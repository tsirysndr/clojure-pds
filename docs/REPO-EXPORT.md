# Repository CAR exports

`com.atproto.sync.getRepo` returns a complete CAR containing the current signed
commit, its MST and record blocks. A `since` query still receives a full export;
incremental export optimization is not implemented. Account status and pending
signing-key rotation checks continue to apply. Record takedowns preserve signed
repository data, so those records remain in full sync exports.

The exporter traverses stored MST edges and leaf records, without following links
inside record values. It excludes historical commits/records that are no longer
reachable. Every emitted block must belong to the account and match its CID.
Shared CIDs are written once, using a temporary PostgreSQL table whose lifetime
is the export transaction. This requires the database role's `TEMPORARY` privilege
([granted to `PUBLIC` by default in PostgreSQL](https://www.postgresql.org/docs/18/ddl-priv.html)). The exporter retains only the
current traversal path and individual blocks in the JVM, rather than a full map
of record bytes or the entire encoded CAR. It does not rebuild the MST.

Preparation holds the account and repository locks for a consistent snapshot.
The CAR is written to a private mode-0600 file in the JVM temporary directory,
opened with `DELETE_ON_CLOSE`. After the database transaction commits, an owned
HTTP body streams that file with 64 KiB writes and backpressure. A slow client
holds no database connection or account lock. Success, HEAD, disconnect, write
failure and server shutdown close the response body and release its file/slot.
Preparation failures close the file and roll back the temporary database state
before any partial CAR is served.

`PDS_REPO_EXPORT_MAX_BYTES` bounds each encoded CAR, including framing. The
default is 268435456 (256 MiB); allowed values are 1048576–17179869184
(1 MiB–16 GiB). Exceeding it returns `413 PayloadTooLarge`; increase it if a
repository outgrows the configured limit. Two process-wide slots cover both
preparation and HTTP delivery and are shared with partial CAR endpoints;
exhaustion returns `503 RepoExportBusy`.
Allocate up to twice the larger of the configured limit and 64 MiB for temporary CAR payload, separately
from the blob staging budget, plus filesystem and PostgreSQL temporary-table
overhead. Disk I/O failures return a sanitized `503 RepoExportUnavailable`.

`getBlocks` streams each requested owned block into a rootless CAR, deduplicating
repeated CIDs. `getRecord` writes the commit and visits only the requested MST
proof path, including the selected record when present or proving absence. The
visitor checks hashes before emitting blocks and does not retain all proof bytes.
Both endpoints stage completely before publishing any response bytes, release
database resources before HTTP delivery, and use the same cleanup contract.
Their existing 63 MiB total block-payload limit remains, with a 64 MiB encoded
CAR cap; `PDS_REPO_EXPORT_MAX_BYTES` affects only full `getRepo` responses.
Individual database block reads are capped at 1 MiB, matching the repository
codec's supported block envelope. Missing or unowned explicitly requested blocks
return `400 BlockNotFound`; corrupt proof/commit data fails without a partial CAR.

The current import verifier still has its independent 64 MiB buffered limit.
Raising the export limit does not raise import capacity. Internal byte-array CAR
helpers remain buffered. Large-repo
load tests, bulk read optimization and deployed relay/migration verification
remain pending; this change establishes bounded payload handling, not a measured
production throughput claim.

Tests compare the new export against the complete existing block set, validate
signatures and reachable records, exercise duplicate content and arbitrary links,
check concurrent-write snapshot boundaries, and verify cleanup after corruption,
capacity exhaustion and size-limit failures. Existing HTTP sync tests pass the
actual response bytes to the pinned upstream repository/proof verifier.
Repeated exports also reuse one pooled connection past pgjdbc's statement-cache
threshold, including after a failed transaction, with no temporary state left
behind and the connection available before response-body consumption.

## CAR reader foundation

The shared CAR v1 decoder uses a stream visitor that reads one frame at a time,
checks each CID and hash before invoking the visitor, and never relies on
`InputStream.available()` to determine EOF. Individual reads are at most 64 KiB;
block payloads are bounded to 1 MiB and the complete encoded input defaults to
64 MiB. The total includes headers, length prefixes and repeated blocks. A frame
that cannot fit in the remaining budget is rejected before allocating its payload.
The reader accepts the existing CIDv1 SHA-256 raw/DAG-CBOR subset, preserves wire
order and visits duplicate occurrences. The existing byte-array decoder collects
these callbacks into its original deduplicated block map.

The input remains owned by the caller. Visitor exceptions and read failures stop
parsing immediately; thread interruption is checked between reads. Callers must
provide transport deadlines and stage visitor effects until successful EOF and
repository/signature verification: a valid prefix does not establish a valid
archive or repository. This reader is a prerequisite for staged imports; the
public import endpoint still buffers the archive and decoded repository today.

Unit tests cover fragmented input, empty/rootless archives, the largest supported
block, exact byte limits, duplicate accounting, malformed lengths, incomplete
frames, invalid CIDs, hash mismatches, visitor failures and caller ownership.
The framing follows the [CAR v1 specification](https://ipld.io/specs/transport/car/carv1/).
