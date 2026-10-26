# Dagger SDK for Clojure

A personal, **unofficial experimental SDK** targeting **Dagger 1.0.0-beta.21**.
It is not maintained or endorsed by the Dagger team.

Write pipelines with ordinary Clojure functions, thread them with `->`, pass
optional arguments as maps, and manage sessions with `with-open`. Engine objects
are lazy; scalar results execute the query. Failures throw `ex-info` with useful
`ex-data`, including GraphQL errors.

## Get started

From this checkout’s root:

```sh
mise trust
mise install
cd clojure
mise trust
mise install
mise exec -- clojure -M:test
```

The root configuration pins Dagger beta.21; this directory pins Temurin JDK 21
and the Clojure CLI. The SDK is not published to Clojars. Add a local dependency
to your application’s `deps.edn`:

```edn
{:deps {personal/dagger {:local/root "/absolute/path/to/dagger-sdk/clojure"}}}
```

```clojure
(require '[dagger.core :as dag]
         '[dagger.container :as container])

(with-open [client (dag/connect)]
  (println (-> (dag/container client)
               (container/from "alpine:3.22")
               (container/with-exec ["echo" "Hello from Clojure!"])
               container/stdout)))
```

`dag/connect` reuses an inherited Dagger session when present, or starts the
Dagger CLI from `PATH`. Closing the client only stops sessions it owns. Run with
`mise exec -- clojure ...` to select this repository’s CLI pin. A live engine
requires Docker or a configured Dagger engine.

Explore the [runnable examples](examples/README.md) for a pipeline and a module.

## Write a Dagger module

Use normal `defn` forms. Mark exported functions with `:dagger/return`; parameter
hints such as `^String` provide argument types. Unmarked helpers stay private to
the application. `from-ns` discovers exported public vars and constructs the
module definition.

```clojure
(ns dagger-main
  (:require [dagger.modules :as modules]))

(defn ^{:dagger/return :string} hello
  "Greet someone."
  [^{:tag String :dagger/default "world"} name]
  (str "Hello, " name "!"))

(defn -main [& _]
  (modules/serve! (modules/from-ns 'dagger-main "HelloClojure")))
```

Use `:dagger/type` metadata for richer parameter types, and `^:dagger/context`
for the injected context containing `:client` and `:parent`. Exported functions
must have one fixed arity. Types include `:string`, `:int`, `:float`, `:boolean`,
`:void`, `[:optional type]`, `[:list type]`, and `[:object "Container"]`.
For custom state, constructors, enums, and additional objects, use the explicit
module map API in [dagger.modules](src/dagger/modules.clj).

Follow the [module example instructions](examples/README.md#run-the-module) to
install this custom SDK, scaffold a module, and invoke it with beta.21. The Go
bootstrap installs the toolchain and generates bindings; your functions and
registration/invocation logic run in Clojure. Generated bindings are kept in
`dagger-sdk/` inside the module project.

## Develop and test

Run from `clojure/`:

```sh
mise exec -- clojure -M:test
mise exec -- clojure -M:generate --check
mise exec -- clojure -M:integration
```

Unit tests cover transport errors, lazy selections, argument encoding, module
registration/invocation, metadata exports, scaffolding, and generated bindings.
The integration command requires an engine. GitHub Actions runs unit tests and
live client checks; module checks can also be run from the repository root:

```sh
mise exec -- python3 scripts/test-module.py clojure
```

Regenerate bindings after updating `schema/introspection.json`:

```sh
mise exec -- clojure -M:generate
```

Edit the generator, not generated namespaces. [Schema provenance](schema/README.md)
describes the bundled snapshot. Module generation uses the engine’s schema, so
installed module dependencies get their own bindings.

## License

Original code is [MIT licensed](LICENSE). Upstream schemas and derived API
documentation retain Apache-2.0 licensing; see [NOTICE](NOTICE).
