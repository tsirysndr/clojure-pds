# Relay announcements

The PDS can request crawling from explicitly configured relays. Announcements are
disabled by default; no public relay is contacted automatically.

```sh
PDS_PUBLIC_URL=https://pds.example.com
PDS_RELAY_URLS=https://relay.example.com,https://other-relay.example.com
PDS_RELAY_INTERVAL_SECONDS=1200
```

`PDS_RELAY_URLS` accepts up to 16 HTTPS origins. Paths, credentials, queries and
fragments are rejected. Duplicate origins are normalized. An empty/unset value
disables the worker. The success interval accepts 60–86,400 seconds and defaults
to 20 minutes. Restart the PDS after changing configuration; all instances sharing
the database should use the same configuration.

The public PDS URL must be an HTTPS origin on port 443. Its hostname is announced,
independently of the local bind address or port. Nonstandard public ports are not
currently announced because the request identifies a hostname rather than a full
endpoint. TLS termination may run in front of the PDS's local HTTP listener.

After the HTTP listener starts, the worker sends an unauthenticated JSON POST to
each configured relay's `/xrpc/com.atproto.sync.requestCrawl`:

```json
{"hostname":"pds.example.com"}
```

Requests use the shared guarded HTTP client with public-address checks, hostname
verification, no redirects or cookies, a five-second deadline and a 64 KiB response
limit. Only the hostname is sent; account credentials and records are not included.
The relay decides whether and when to crawl the PDS. A successful announcement
does not prove firehose subscription, indexing, or AppView visibility.

## Scheduling and retries

Migration 035 stores one schedule per relay/PDS hostname in
`relay_announcements`. Initial announcements are due immediately. Successful
responses schedule the next periodic announcement, including while the PDS is
idle. Restarting does not reset an existing schedule.

A worker claims one due row at a time with a 30-second lease. Claims skip locked
rows; network requests run outside database transactions. Multiple PDS instances
share the schedule. A crashed worker's lease expires, and a late worker cannot
overwrite a replacement worker's result. Requests are at least once: a lost
response or interrupted process can cause a repeated crawl request.

Failures retry after 5 seconds, then 10, 20, and so on, capped at one hour. Valid
`Retry-After` values on HTTP 429/503 can extend that delay, up to 24 hours. Success
resets the failure counter. HTTP errors, including relay bans, remain failed
announcements and retry; they never change account or repository state. Only the
HTTP status and a fixed error category are stored, not remote response content.

Removing a relay from configuration stops future requests from restarted workers.
Its diagnostic row remains in PostgreSQL. Changing the public hostname creates a
separate schedule; old hostname rows are not sent by the new configuration.
Disabling announcements does not ask a relay to stop an existing crawl.

Inspect scheduling without exposing credentials:

```sql
SELECT relay_url, hostname, attempts, next_attempt_at, lease_until,
       last_success_at, last_status, last_error
FROM relay_announcements
ORDER BY relay_url, hostname;
```

`last_success_at` means that a request received a 2xx response. For actual
federation verification, separately confirm that the relay consumes the public
`subscribeRepos` stream, reconnects using cursors, verifies signed commits and
serves the expected account updates downstream.

## Verification

Tests cover request shape over real TLS, absence of ambient authentication and
cookies, redirect/encoding/response-size failures, bounded retry headers, durable
restart schedules, exponential backoff, concurrent leases, late completions,
configuration filtering, and worker startup/shutdown. The main process test
continues to start and stop with announcements disabled. No external relay is
contacted during these tests.

A deployed instance has since announced itself to the production Bluesky relay
at `https://bsky.network`, which answered `200` and reports the host through
`com.atproto.sync.getHostStatus`:

```json
{"hostname":"…","status":"active","accountCount":0,"seq":-1}
```

That establishes the announcement contract against a real relay. It does not
establish federation: no repository event has crossed the link, so commit
validation and AppView visibility remain unverified.

Sources: [requestCrawl Lexicon](https://github.com/bluesky-social/atproto/blob/main/lexicons/com/atproto/sync/requestCrawl.json),
[reference PDS crawler notifications](https://github.com/bluesky-social/atproto/blob/main/packages/pds/src/crawlers.ts),
[sync specification](https://atproto.com/specs/sync).
