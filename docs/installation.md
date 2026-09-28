# Installation

What clojure-pds needs on a machine, how to run it from source, and the
packaged alternatives.

## Requirements

| Component  | Requirement                                                                  |
| ---------- | ---------------------------------------------------------------------------- |
| JDK        | Temurin `25.0.3+9.0.LTS`, pinned in `mise.toml` and installed by mise         |
| Clojure    | The official Clojure CLI (`clojure`); CI pins `1.12.4.1618`                   |
| PostgreSQL | 14 or newer (18 used in CI); optional when using the SQLite backend           |
| SQLite     | Bundled through the JDBC driver — nothing to install; single node only        |
| Node.js    | 24; only to rebuild the [account frontend](ACCOUNT-SECURITY.md#building-the-interface) or run conformance tooling |
| Docker     | Optional; runs the bundled PostgreSQL and the prebuilt image                  |
| Nix        | Optional; `flake.nix` provides a launcher package and a development shell     |

## From source

```sh
git clone https://github.com/tsirysndr/clojure-pds.git
cd clojure-pds
mise trust && mise install
```

Dependencies are fetched on first run. Start the server with
`mise exec -- clojure -M:run` after following [Get started](get-started.md);
run migrations alone with `mise exec -- clojure -M:migrate`. Operator
commands live behind their own aliases: `-M:identity`, `-M:plc-recovery`,
`-M:master-key`, and `-M:account-admin`.

## Docker

`ghcr.io/tsirysndr/clojure-pds:latest` is a multi-architecture image
(amd64/arm64) that runs the server from source with a prebuilt dependency
cache and the committed account UI:

```sh
docker run --rm \
  --env-file pds.env \
  -p 127.0.0.1:3000:3000 \
  ghcr.io/tsirysndr/clojure-pds:latest
```

It listens on `0.0.0.0:3000`, health-checks `/xrpc/_health`, and keeps all
state in the configured database. The `docker` workflow publishes it on `v*`
tags or manual dispatch; `Dockerfile` in the repository builds the same image
locally.

## Nix

The flake packages a launcher and a development shell:

```sh
nix build .#clojure-pds     # result/bin/clojure-pds [alias], defaults to run
nix develop                 # JDK, Clojure CLI, Node, PostgreSQL client tools
```

The launcher runs from the store source and fetches Maven/git dependencies on
first start into `$XDG_CACHE_HOME/clojure-pds`; they are deliberately not
vendored into the store. The `nix` workflow builds and smoke-tests the flake
on manual dispatch.

## Verifying an installation

```sh
mise exec -- clojure -M:test        # unit and conformance-fixture tests
bash scripts/test-postgres.sh      # full matrix on an isolated PostgreSQL cluster
bash scripts/test-sqlite.sh        # SQLite-backend portion, no database service
```

See [Development](DEVELOPMENT.md) for the complete test matrix, CI layout,
and the REPL workflow.
