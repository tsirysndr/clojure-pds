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
