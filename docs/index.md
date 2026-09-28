---
layout: layouts/home.vto
title: clojure-pds
description: An AT Protocol Personal Data Server in Clojure, on PostgreSQL or a zero-configuration SQLite file.
---

clojure-pds hosts did:web and did:plc accounts with signed repositories,
verified CAR import/export, blob storage, a WebSocket firehose, OAuth with
DPoP and scoped permissions, an authenticated streaming service proxy, and
administrative tooling — 71 XRPC endpoints validated against pinned upstream
Lexicons. It is in development and not yet a fully federating PDS; the
[compatibility matrix](COMPATIBILITY.md) records exactly what is verified and
what remains.

<div class="card-grid">
  <a class="card" href="/get-started/">
    <h3>Get started →</h3>
    <p>Boot a server with nothing but a master key and make your first request in minutes.</p>
  </a>
  <a class="card" href="/installation/">
    <h3>Installation →</h3>
    <p>Toolchain prerequisites, source checkout, the Docker image, and the Nix flake.</p>
  </a>
  <a class="card" href="/configuration/">
    <h3>Configuration →</h3>
    <p>Every environment variable, the database backends, and startup migrations.</p>
  </a>
  <a class="card" href="/deployment/">
    <h3>Deployment →</h3>
    <p>The VPS checklist from an empty host to the first live federation checks.</p>
  </a>
</div>

## What is in the box

- The pinned reference-PDS route surface: `com.atproto` server, repo, sync,
  identity, and admin endpoints with Lexicon-validated inputs and observed
  output conformance for every implemented route.
- Signed repositories with deterministic Merkle Search Trees, version-3
  commits, MST inclusion/absence proofs, streaming CAR export, and
  disk-staged verified import.
- Durable ordered events over `com.atproto.sync.subscribeRepos`, with cursor
  replay, backpressure, and opt-in relay announcements.
- OAuth: PAR, PKCE, DPoP with durable replay protection, browser
  authorization, scoped and transitional permissions, and epoch-scoped
  sessions for deactivated migration accounts.
- Account security: app passwords, email flows, TOTP with recovery codes,
  passkeys, and a React account interface served under a strict CSP.
- Operations: PostgreSQL or single-file SQLite persistence, S3-compatible
  blob storage, Redis-shared rate limits, checksummed migrations, offline
  master-key rotation, and a tested backup/restore drill.

## Honest status

Everything above is verified against local fixtures and pinned upstream
implementations (`@atproto/repo`, `@atproto/xrpc-server`, `@did-plc/lib`, the
reference OAuth client). No live relay, AppView, or deployed Bluesky client
has consumed this PDS yet — treat deployments as experiments and read the
[roadmap](ROADMAP.md) for what remains.

## License

clojure-pds is released under the [MIT License](https://github.com/tsirysndr/clojure-pds/blob/main/LICENSE).
Vendored conformance fixtures retain their upstream CC0 license, and vendored
Lexicons retain their upstream MIT/Apache notices.
