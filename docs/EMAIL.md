# Cloudflare Worker email

Configure all three `PDS_EMAIL_*` variables to enable delivery; leave all unset to
disable it. Partial configuration fails startup. Confirmation/reset endpoints fail
explicitly when delivery is disabled.

The PDS POSTs JSON `{to, from, subject, text, idempotencyKey}` with Bearer
`Authorization` and an `Idempotency-Key` header. Any 2xx means accepted. HTTP is
allowed only on loopback; redirects are never followed. Timeouts, 408, 429, and 5xx
retry with exponential backoff, up to ten attempts. Other failures stop retrying.

Messages are queued transactionally with account changes in `email_outbox`.
Workers claim messages with expiring leases; queued delivery survives a restart.
Sent payloads are erased. Failed/pending payloads contain private account messages;
protect the database and backups. Inspect sanitized `last_error` codes for failures.

[examples/email-worker](../examples/email-worker) contains the Worker and its contract
tests. Configure the sender in `wrangler.toml`, set the shared secret using
`wrangler secret put PDS_EMAIL_TOKEN`, and deploy using Wrangler. It requires
Cloudflare Email Service, a verified sending domain, and Durable Objects. No Worker
has been deployed and tests send no real email. See Cloudflare's
[Workers email API](https://developers.cloudflare.com/email-service/api/send-emails/workers-api/).

The Worker stores hashed receipts to deduplicate successful retries. Delivery is
**at least once**: a crash after sending but before receipt persistence can produce
a duplicate. Receipts are retained indefinitely; plan retention for larger systems.
