# Current limits

- Record write APIs enforce AT data-model and blob-ownership validation. A pinned local
  catalog validates 17 common record types and their dependencies by default.
  `validate: true` requires a known schema; `validate: false` skips schema checks.
  Results report `validationStatus: "valid"` or `"unknown"`; skip mode omits it.
  See [catalog provenance and scope](../resources/lexicons/README.md).
- All 71 implemented protocol endpoints have pinned Lexicon definitions. JSON
  request bodies and query parameters are validated when handlers read them,
  preserving authentication order. Defaults, required/nullable fields, formats,
  array parameters and nested unions are checked; extension fields remain allowed.
  Binary uploads retain their bounded readers. Integration tests check actual
  responses for every implemented endpoint and all repository event variants
  against local and pinned upstream validators. Dynamic Lexicon resolution and
  external client/relay conformance remain unfinished.
- MSTs match upstream root fixtures; ordinary writes rebuild them, O(n). Large repos
  need incremental updates. Historical blocks are retained; exports contain the
  current graph. Public full CAR exports stream through private temporary files,
  visiting stored blocks without rebuilding the tree or buffering the whole CAR.
  `PDS_REPO_EXPORT_MAX_BYTES` defaults to 256 MiB, with two concurrent export slots;
  `getBlocks` and `getRecord` share those slots and stream partial CARs under their
  separate 63 MiB payload limit. See [repository export limits](REPO-EXPORT.md).
  CAR imports stage payloads on disk and replace the full current record set,
  retaining CID/path metadata in memory. Imported canonical roots are reused when
  signing the destination commit. Historical repository-block garbage collection
  and large-repository performance work remain unfinished.
  `com.atproto.sync.listReposByCollection` enumerates active repositories holding
  records in one collection through an indexed keyset scan (limit 1–2000, default
  500). `com.atproto.temp.checkSignupQueue` reports `activated=true` for
  authenticated accounts; this PDS has no signup queue.
- Commit events persist signed CAR proofs and previous-value operations for
  inductive verification. Records are limited to 1,000,000 encoded bytes and
  commit proofs to 2,000,000 bytes; oversized batches roll back. The upstream
  conformance suite verifies signatures and reconstructs previous MST roots.
  `com.atproto.sync.subscribeRepos` delivers these events over binary WebSockets.
  Without a cursor it starts live; `cursor=0` replays the backfill window, and a
  nonzero cursor resumes after the last processed sequence (matching the reference
  PDS). The default window is 24 hours, configured with
  `PDS_FIREHOSE_BACKFILL_SECONDS`. Old cursors receive `OutdatedCursor`; future
  cursors fail. Account availability is checked before each content frame.
  `PDS_FIREHOSE_MAX_CLIENTS` defaults to 64 per process and
  `PDS_FIREHOSE_MAX_BACKLOG` to 1,000 new events. Sends have a five-second deadline
  and only one message in flight. Configure your HTTPS proxy to pass WebSocket
  upgrades. The replay window currently limits queries, not physical event retention.
- Blob uploads accept zero bytes through 5 MiB and stream through private temporary
  files, hashing before publication and rechecking authorization after the client
  read. Sixteen upload slots cap staged payload at 80 MiB per process; overload
  returns 503. Known-length streams persist bytes into PostgreSQL `bytea` by
  default or a [configurable S3-compatible backend](S3.md). PostgreSQL always
  holds ownership and metadata. Temporary uploads are private until referenced;
  re-uploading them renews their grace period. `PDS_BLOB_TEMP_TTL_SECONDS` defaults
  to 86400 (24 hours), with an allowed range of 3600–2592000 seconds. A worker checks
  once per minute, collecting at most 50 expired unreferenced blobs from one account.
  Losing the final current record reference removes blob metadata in that record
  transaction. PostgreSQL bytes are removed immediately; S3 locators enter the
  durable deletion queue. Fresh S3 uploads use unique object keys so a delayed
  deletion cannot erase a later upload with the same CID. Public downloads verify
  size/CID into private temporary files using 64 KiB reads, then stream with
  backpressure and automatic cleanup. Sixteen concurrent staging/delivery slots
  cap temporary payload at 80 MiB per process; excess downloads return 503.
  MIME sniffing, and discovering S3 orphans left by failed metadata commits
  remain unfinished.
- Rate limits default to bounded, per-process memory with 120 requests/IP/minute.
  [Optional Redis](REDIS.md) shares counters across instances. Untrusted
  forwarding headers are ignored; reverse proxies need an appropriate limit policy.
- OAuth, external service/relay interoperability, and production
  operations remain on the roadmap. The official client SDK and firehose
  consumer are exercised against a local server; a hosted relay or AppView on
  the live network has not yet consumed this PDS.
