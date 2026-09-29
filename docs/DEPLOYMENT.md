# Deployment

This is a development implementation: local and pinned-upstream tests pass, but
no live relay, AppView, or deployed Bluesky client has consumed this PDS yet.
Deploy it for experiments with throwaway accounts, not as the home of an
account you care about. See [the compatibility matrix](COMPATIBILITY.md).

## Requirements

- PostgreSQL 14+ (managed or self-hosted; use `sslmode=verify-full` off-host).
  For a single small host, the experimental [SQLite backend](SQLITE.md) needs
  no database service — back up its data file instead.
- An HTTPS reverse proxy (Caddy, nginx, Traefik) that passes WebSocket
  upgrades and preserves the `Host` header.
- Wildcard DNS for the handle domain (`*.example.com`) plus the PDS hostname.
- A stable `PDS_MASTER_KEY`, generated once and backed up separately from the
  database; see [master-key handling](get-started.md) and
  [rotation](MASTER-KEY.md).

Minimal production environment:

```sh
PDS_HOSTNAME=pds.example.com
PDS_PUBLIC_URL=https://pds.example.com
PDS_USER_DOMAIN=example.com
PDS_DID_METHOD=plc
PDS_ENABLE_SIGNUP=true
PDS_REQUIRE_INVITE_CODE=true
PDS_ADMIN_PASSWORD=<random, 16+ characters>
PDS_MASTER_KEY=<base64url 32 bytes, backed up>
PDS_DATABASE_URL=jdbc:postgresql://db.internal:5432/clojure_pds?sslmode=verify-full
PDS_DATABASE_USER=pds
PDS_DATABASE_PASSWORD=<secret>
```

Every variable is described in [the configuration reference](CONFIGURATION.md).
Startup runs checksummed migrations under an advisory lock before binding HTTP.

## Single host with systemd

A small always-on machine can run the server from a checkout with the
[SQLite backend](SQLITE.md), no root, and no database service. Install the
Clojure CLI and a JDK 21+, prefetch dependencies with `clojure -P -M:run`,
create the schema with `clojure -M:migrate`, then keep configuration in a
`0600` environment file:

```sh
install -d -m 700 ~/.config/clojure-pds ~/.local/share/clojure-pds
umask 077
cat > ~/.config/clojure-pds/clojure-pds.env <<'EOF'
PDS_HOST=127.0.0.1
PDS_PORT=3000
PDS_HOSTNAME=pds.example.com
PDS_PUBLIC_URL=https://pds.example.com
PDS_USER_DOMAIN=example.com
PDS_DID_METHOD=plc
PDS_SQLITE_PATH=/home/you/.local/share/clojure-pds/clojure-pds.sqlite3
PDS_ENABLE_SIGNUP=true
PDS_REQUIRE_INVITE_CODE=true
PDS_APPVIEW_SERVICE=did:web:api.bsky.app#bsky_appview
JAVA_OPTS=-Xmx768m
EOF
printf 'PDS_MASTER_KEY=%s\n' "$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')" \
  >> ~/.config/clojure-pds/clojure-pds.env
```

Leaving `PDS_DATABASE_URL`, `PDS_DATABASE_USER` and `PDS_DATABASE_PASSWORD`
unset is what selects SQLite. `PDS_APPVIEW_SERVICE` is a DID service
reference, not a URL. A user unit needs no root, and `loginctl enable-linger`
keeps it running while nobody is logged in:

```ini
[Service]
WorkingDirectory=/home/you/clojure-pds
EnvironmentFile=/home/you/.config/clojure-pds/clojure-pds.env
Environment=PATH=/home/you/.local/share/mise/shims:/usr/local/bin:/usr/bin:/bin
ExecStart=/home/you/.local/share/mise/shims/clojure -M:run
Restart=on-failure
```

Put Caddy in front, bound to a high port when `/etc/caddy` is not writable:

```text
:8080 {
	reverse_proxy 127.0.0.1:3000 {
		lb_try_duration 25s
		lb_try_interval 500ms
	}
}
```

Do not rewrite `Host`. The server resolves `/.well-known/did.json` from it, so
a `header_up Host` directive makes the service DID document answer
`AccountNotFound`. Terminating TLS at Caddy instead needs `pds.example.com`
in place of `:8080` and nothing else. Behind a Cloudflare Tunnel, point the
ingress at `http://localhost:8080`; WebSocket upgrades reach
`subscribeRepos` over HTTP/1.1, and an HTTP/2 request answers `426` by design.

The retry window matters more than it looks. Starting from source takes about
twenty seconds, and without it the proxy fails the dial immediately and answers
`502`. A CDN in front will replace that with its own error page, which carries
no `Access-Control-Allow-Origin`, so a browser application reports a routine
restart as a CORS failure rather than the gateway error it is — and the CORS
headers this server sets never reach it, because the response never came from
this server. Holding the request across the gap avoids the error entirely.

Back up the SQLite file and the master key separately, and read logs with
`journalctl --user -u clojure-pds` — or `journalctl
_SYSTEMD_USER_UNIT=clojure-pds.service` on hosts whose per-user journal is
not written.

## Docker

`Dockerfile` builds a multi-architecture image that runs the server from
source with a prebuilt dependency cache and the committed account UI. The
`docker` workflow publishes it to `ghcr.io/tsirysndr/clojure-pds` (tag
`latest`, plus version tags) on a `v*` tag push or manual dispatch.

```sh
docker run --rm \
  --env-file pds.env \
  -p 127.0.0.1:3000:3000 \
  ghcr.io/tsirysndr/clojure-pds:latest
```

The container listens on `0.0.0.0:3000`, reports `/xrpc/_health` as its
health check, and stores nothing locally: PostgreSQL holds all state, so point
`PDS_DATABASE_URL` at a database with backups. Run migrations alone with
`docker run ... ghcr.io/tsirysndr/clojure-pds:latest clojure -M:migrate`.

## Nix

`flake.nix` packages a launcher (`nix build .#clojure-pds`, run as
`result/bin/clojure-pds [alias]`, defaulting to the `run` alias) and a
development shell (`nix develop`) with the JDK, Clojure CLI, Node, and
PostgreSQL client tools. The launcher runs from the store source and fetches
Maven/git dependencies on first start into `$XDG_CACHE_HOME/clojure-pds`;
they are deliberately not vendored into the store. The `nix` workflow builds
and smoke-tests the flake on manual dispatch.

## First live checks

1. `curl https://pds.example.com/xrpc/_health` and
   `.../xrpc/com.atproto.server.describeServer` through the proxy.
2. Create an invite code with the admin API, then a **throwaway** account at
   `https://pds.example.com/account`.
3. Try the official Bluesky app against the handle, ask a relay to crawl the
   host, and check whether posts appear through the public AppView. Failures
   here are the remaining "deployed conformance" roadmap work — capture them.

Operational gaps to plan around: no metrics/structured logging yet, historical
repository blocks and orphaned S3 objects are not garbage-collected, MST
rebuilds are O(n) per write, and email needs the
[Cloudflare Worker](EMAIL.md) deployed separately.
