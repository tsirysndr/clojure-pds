# Deployment

This is a development implementation: local and pinned-upstream tests pass, but
no live relay, AppView, or deployed Bluesky client has consumed this PDS yet.
Deploy it for experiments with throwaway accounts, not as the home of an
account you care about. See [the compatibility matrix](COMPATIBILITY.md).

## Requirements

- PostgreSQL 14+ (managed or self-hosted; use `sslmode=verify-full` off-host).
- An HTTPS reverse proxy (Caddy, nginx, Traefik) that passes WebSocket
  upgrades and preserves the `Host` header.
- Wildcard DNS for the handle domain (`*.example.com`) plus the PDS hostname.
- A stable `PDS_MASTER_KEY`, generated once and backed up separately from the
  database; see [master-key handling](GETTING-STARTED.md) and
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
