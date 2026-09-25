# clojure-pds

An AT Protocol Personal Data Server in Clojure, built in small, independently
reviewable commits. **Work in progress:** this is not yet a federating PDS.

## Development

Install the Clojure CLI and [mise](https://mise.jdx.dev/). The project pins
Temurin JDK `25.0.3+9.0.LTS` in `mise.toml`.

```sh
mise trust
mise install
mise exec -- clojure -M:test
mise exec -- clojure -M:run
```

Dependencies are pinned in `deps.edn`. Tests use `clojure.test` and are discovered
from `test/**/*_test.clj`; no external test runner is required.
With mise activated in your shell, `clojure -M:test` and `clojure -M:run` also
use the pinned JDK. Explicit `mise exec` works without shell activation.

See [the roadmap](docs/ROADMAP.md) for implementation order and acceptance criteria.
Each feature commit includes its tests and relevant documentation. No license has
been selected yet.

## Configuration

Configuration comes from environment variables (a `.env` file is not loaded).
Invalid values fail before the server starts.

| Variable | Default | Meaning |
| --- | --- | --- |
| `PDS_HOST` | `127.0.0.1` | HTTP bind address |
| `PDS_PORT` | `3000` | TCP port; `0` selects an ephemeral port |
| `PDS_HOSTNAME` | `localhost` | Lowercase DNS hostname for the service DID |

The default `did:web:localhost` is a development placeholder, not a publicly
resolvable identity. Public DID hosting and TLS arrive in later milestones.

## HTTP foundation

```sh
curl http://127.0.0.1:3000/xrpc/_health
# {"version":"0.1.0-dev"}
```

Health reports process liveness only. Unknown paths return a JSON XRPC error and
HTTP 404; unsupported methods return 405 with an `Allow` header. GET routes also
support HEAD. Stop with Ctrl-C to release the listener and worker threads.

`pds.xrpc` dispatches pure request/response maps; `pds.http` adapts them to the
JDK HTTP server. The initial adapter supports UTF-8 response bodies. Binary
streams, WebSockets, CORS, authentication, persistence, and federation are still
on the roadmap. Local binding is the default; this milestone is for development.
