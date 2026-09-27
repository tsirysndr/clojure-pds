# Configuration

Configuration comes from exported environment variables; `.env` files are not
automatically loaded. See [.env.example](../.env.example) for a complete annotated
template. Topic-specific settings are documented with their features:
[S3 blobs](S3.md), [Redis rate limits](REDIS.md), [relay announcements](RELAY.md),
[identity cache](IDENTITY-CACHE.md), [service proxy](PROXY.md),
[repository exports](REPO-EXPORT.md), [imports](REPO-IMPORT.md) and the
[database pool](DATABASE.md).

| Variable | Default | Purpose |
| --- | --- | --- |
| `PDS_HOST` | `127.0.0.1` | Bind address |
| `PDS_PORT` | `3000` | Port; `0` requests an ephemeral port |
| `PDS_HOSTNAME` | `localhost` | Service DID hostname |
| `PDS_PUBLIC_URL` | `http://localhost:3000` | Public PDS origin; HTTPS outside loopback |
| `PDS_USER_DOMAIN` | `PDS_HOSTNAME`, or `pds.localhost` if unset | Hosted handle suffix |
| `PDS_IDENTITY_CACHE_TTL_SECONDS` | `300` | Public remote identity cache TTL; 0–3,600; `0` disables; [freshness policy](IDENTITY-CACHE.md) |
| `PDS_IDENTITY_CACHE_MAX_ENTRIES` | `1024` | Maximum cached/in-flight identity entries; 1–10,000 |
| `PDS_ENABLE_SIGNUP` | `false` | Enable account creation |
| `PDS_MASTER_KEY` | required | Stable base64url-encoded 32-byte master key |
| `PDS_DATABASE_URL` | `jdbc:postgresql://127.0.0.1:5432/clojure_pds` | PostgreSQL JDBC URL |
| `PDS_DATABASE_USER` | `pds` | Database role |
| `PDS_DATABASE_PASSWORD` | empty | Database password |
| `PDS_DB_POOL_SIZE` | `20` | Maximum PostgreSQL connections per server; 1–256; [pool lifecycle](DATABASE.md) |
| `PDS_DB_POOL_TIMEOUT_MS` | `5000` | Maximum wait to borrow a connection; 500–60,000 ms |
| `PDS_BLOB_BACKEND` | `postgres` | `postgres` or `s3`; [S3 settings](S3.md) |
| `PDS_RATE_LIMIT_BACKEND` | `memory` | `memory` or `redis`; [Redis settings](REDIS.md) |
| `PDS_RECORD_WRITE_RATE_LIMIT_ENABLED` | `true` | `false` disables record-write limits; [write budget settings](REDIS.md#record-write-overrides) |
| `PDS_RATE_LIMIT_REQUESTS` | `120` | Requests per IP per window |
| `PDS_RATE_LIMIT_WINDOW_SECONDS` | `60` | Fixed window duration |
| `PDS_RELAY_URLS` | unset | Comma-separated relay HTTPS origins; [durable announcements](RELAY.md) |
| `PDS_RELAY_INTERVAL_SECONDS` | `1200` | Interval between successful relay announcements |
| `PDS_EMAIL_WORKER_URL` | unset | Email Worker HTTPS endpoint |
| `PDS_EMAIL_WORKER_TOKEN` | unset | Worker shared secret |
| `PDS_EMAIL_FROM` | unset | Verified sending address |

For hosted PostgreSQL use `sslmode=verify-full`. Startup runs checksummed,
transactional migrations under an advisory lock before binding HTTP. Run migrations
alone with `mise exec -- clojure -M:migrate`. Applied migrations are immutable;
add numbered SQL files and register them in `pds.db/migrations`.
