# Administrative takedowns

Set `PDS_ADMIN_PASSWORD` to enable administrative XRPC routes. Requests require
HTTP Basic credentials with username `admin`; ordinary account sessions and
OAuth grants do not grant administrative access. Keep this credential private
and use HTTPS outside local development.

`com.atproto.admin.getSubjectStatus` accepts these selectors:

| Subject | Query parameters | Subject type in response |
| --- | --- | --- |
| Account | `did` | `com.atproto.admin.defs#repoRef` |
| Record | `uri=at://DID/collection/rkey` | `com.atproto.repo.strongRef` |
| Blob | `did` and `blob=CID` | `com.atproto.admin.defs#repoBlobRef` |

Blob selectors take precedence over record selectors, then account selectors.
Record URIs must contain a DID, collection and record key. Record status includes
the current CID even while hidden. Unknown record/blob subjects return 400
`NotFound`; unknown accounts retain the account API's `AccountNotFound` error.

POST a subject and status to `com.atproto.admin.updateSubjectStatus`, for example:

```json
{
  "subject": {
    "$type": "com.atproto.repo.strongRef",
    "uri": "at://did:plc:example/com.example.post/one",
    "cid": "<current record CID>"
  },
  "takedown": {"applied": true, "ref": "case-123"}
}
```

Use `{"applied": false}` to restore availability. For record/blob takedowns,
omitting `ref` generates a timestamp reference; an empty reference is also valid.
Omitting `takedown` leaves its current value unchanged. `deactivated` applies only
to account subjects. Existing account takedowns preserve the independent
activation state, and restoring an account cannot bypass migration checks.

Record takedowns hide the indexed record from `com.atproto.repo.getRecord` and
`listRecords`. Filtering occurs before pagination, in either direction. The flag
belongs to the current record URI, so ordinary updates and replacement imports
preserve it for retained paths. As in the reference implementation, the strongRef
CID is validated but is not a compare-and-swap condition. Deleting a record ends
that indexed record's lifetime; recreating it starts without the old flag.

Record moderation does not rewrite signed repository data, change the head or
revision, or publish a repository mutation. Sync proofs, block downloads,
repository exports and already sequenced events retain that data. Record flags
also do not implicitly take down attached blobs. This is local service moderation,
not a deletion or a network-wide erasure mechanism. Account takedowns continue
to block public repository access and emit account status events.

Blob takedowns apply per DID and CID. They reject downloads, repeated uploads of
the same bytes and new record writes referencing that blob, including legacy
CID/MIME references recognized by the reference index. Another account's
copy is independent. Existing references and sync blob listings remain intact;
the blob is stored, so it is not reported as missing. Imports preserve blob
status. Restoring the flag makes the original stored bytes available again.

Both PostgreSQL and S3 backends enforce the flag before reading bytes. S3 objects
stay at their private immutable locators; takedowns require no provider I/O and
create no deletion jobs. This server does not publish direct object URLs or use
an external public CDN. Keep the bucket private: direct provider access is outside
the PDS moderation boundary. Existing in-flight responses cannot be recalled.
Ordinary last-reference removal, temporary-upload expiry and account deletion
still remove blob metadata and use the existing durable S3 deletion queue. Flags
are not a permanent content denylist after the stored object is removed.

Moderation and authenticated mutations share the account lock, then lock the
subject row. Status updates commit atomically and survive reconnection. Tests
cover HTTP authorization, invalid selectors, pagination, identical content in
separate accounts, import/update preservation, batch rollback, concurrent writes,
unchanged signed exports/proofs, and PostgreSQL/S3 restoration.

Reference behavior was checked at upstream revision
`7a857989751ae31518509d69ab7194a922064f3d`:
[record takedowns](https://github.com/bluesky-social/atproto/blob/7a857989751ae31518509d69ab7194a922064f3d/packages/pds/src/actor-store/record/transactor.ts),
[record reads](https://github.com/bluesky-social/atproto/blob/7a857989751ae31518509d69ab7194a922064f3d/packages/pds/src/actor-store/record/reader.ts),
[blob enforcement](https://github.com/bluesky-social/atproto/blob/7a857989751ae31518509d69ab7194a922064f3d/packages/pds/src/actor-store/blob/transactor.ts),
and [sync record proofs](https://github.com/bluesky-social/atproto/blob/7a857989751ae31518509d69ab7194a922064f3d/packages/pds/src/api/com/atproto/sync/getRecord.ts).
