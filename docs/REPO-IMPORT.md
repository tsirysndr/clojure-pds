# Repository imports

`com.atproto.repo.importRepo` accepts a complete signed version-3 repository CAR.
It requires a primary session or OAuth `account:repo?action=manage` permission;
record-write permission alone does not authorize replacement. Inactive migration
destinations use their retained source DID signing key. Local backup restoration
uses the account's current signing key. Missing or unrelated blocks cannot satisfy
the committed graph, and arbitrary record links do not grant block ownership.

The endpoint authenticates and snapshots account/repository state in a short
transaction, then streams the upload into a private mode-0600 temporary file with
`DELETE_ON_CLOSE`. The CAR reader validates framing and hashes every block before
storing its payload. Duplicate CIDs consume wire budget each time but occupy file
space once. An in-memory index stores CIDs, offsets and lengths, not block bytes.
The request body remains owned by the HTTP transport.

Verification reads staged blocks individually, rechecking disk bytes against
their CIDs. It verifies the DID, signing key, signature, commit fields and revision,
complete ordered MST, valid record paths, canonical tree root and record objects.
It retains CID sets and path mappings. Canonical reconstruction computes node
hashes without collecting all encoded tree nodes. Record values are decoded one
at a time, without applying today's Lexicons to historical objects. Neither upload
reading nor verification holds a database connection or account/repository lock.

After verification, one transaction reauthenticates the caller and compares the
account status, repository head, signing key and retained source document with
the snapshot. Revoked authority is refused; changed state returns `409 InvalidSwap`.
Only reachable verified blocks gain account ownership. The transaction replaces
records and blob references, preserves matching record takedowns, signs the
verified MST root with the destination key and advances beyond both revisions.
It does not rebuild the MST during publication. Active accounts emit a sync
checkpoint; inactive accounts remain private. Blocks, ownership, references,
records, head, import progress and events roll back together on failure.

## Resource limits and cleanup

- The encoded input limit is 64 MiB, including header, frame lengths and duplicate
  blocks. This is independent of `PDS_REPO_EXPORT_MAX_BYTES`. Larger uploads return
  `413 PayloadTooLarge`; making a larger export does not increase import capacity.
- Two process-wide slots cover upload, verification and publication. Exhaustion
  returns `503 RepoImportBusy`. Budget up to **128 MiB of temporary payload**,
  separately from export and blob staging, plus filesystem overhead.
- Wire reads are at most 64 KiB, CAR block payloads at most 1 MiB, and encoded
  records at most 1,000,000 bytes. In-memory CID indexes, path mappings and canonical
  reconstruction still scale with the number of blocks/records. This is bounded
  payload streaming, not a constant-heap or incremental-MST implementation.
- Declared lengths are validated and checked against bytes received. Truncated
  uploads and malformed archives return `400 InvalidRequest`. A 30-second upload
  deadline is checked between reads; Jetty also has a 30-second idle timeout.
  The caller's thread interruption is checked between CAR and staged-file reads.
- Temporary-file creation/read/write errors return a sanitized
  `503 RepoImportUnavailable`. All paths close the file and release the import
  slot, including malformed input, failed verification, revocation, conflicting
  writes, transaction failure and disconnected HTTP clients.

The buffered protocol helpers remain available to internal callers and test
oracles. Remote migration download helpers still buffer their responses. Large
repository throughput/heap benchmarks, incremental mutations and deployed
reference-PDS migration remain pending.

Tests cover a fragmented multi-megabyte import while forbidding buffered helpers,
both signing curves, duplicate staging, disk corruption, actual chunked HTTP and
socket disconnects. A disk-read failure after the first published block verifies
transaction rollback; a one-connection pool proves upload reading holds no database
connection or account lock. Existing tests cover source signatures, canonical-tree
rejection, ownership isolation, legacy/OAuth revocation and concurrent conflicts.
