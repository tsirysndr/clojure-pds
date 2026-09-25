# clojure-pds

An AT Protocol Personal Data Server in Clojure, built in small, independently
reviewable commits. **Work in progress:** this is not yet a federating PDS.

## Development

Install the Clojure CLI and JDK 25 (recommended; JDK 21 or newer required).

```sh
clojure -M:test
```

Dependencies are pinned in `deps.edn`. Tests use `clojure.test` and are discovered
from `test/**/*_test.clj`; no external test runner is required.

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
