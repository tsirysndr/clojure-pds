# Dagger CI

The thin entrypoint `.dagger/ci/src/dagger_main.clj` starts the module. Pipeline registration lives in `src/ci/pipeline.clj`, with checks in `src/ci/` namespaces and shared container helpers kept separately. The Clojure SDK is vendored in `.dagger/sdk/`. GitHub Actions supplies the checkout and Dagger CLI; containers supply the build tools.

Use Dagger **v1.0.0-beta.21** with a supported container engine:

```sh
cd .dagger
dagger api call -m ci integration --source ..
dagger api call -m ci sqlite --source ..
dagger api call -m ci frontend --source ..
dagger api call -m ci nix --source ..
```

Functions return success only after the checks finish. Build, test, and service errors fail the workflow. Node-based tools use **Node 24**, and Bun is pinned to 1.4.2. Image manifests are pinned in `dagger.lock`.

Dagger manages service lifetimes and private networking. Named cache volumes stay inside the Dagger engine; these workflows do not snapshot or upload volumes to GitHub Actions cache. A fresh hosted runner may start with cold caches. Module requests allow up to one hour, with the GitHub job timeout providing the outer limit.

## Editing the pipeline

Keep orchestration in the module language and invoke tools with argument arrays. SDK-generated bindings are ignored under the module directory. Dagger generates them automatically when loading the module. The SDK provenance and licenses are under `.dagger/sdk/`.

For an engine-free module/schema check:

```sh
cd .dagger/ci
mkdir -p dagger-sdk
cp -R ../sdk/clojure/. dagger-sdk/
clojure -M:test
```

The SDK bootstrap uses JDK 21; the application tests use the existing Dockerfile's JDK 25 builder and Clojure CLI 1.12.4.1618.

The integration function provisions PostgreSQL 18, Redis, and Moto S3 as Dagger services, including backup and upstream conformance tests. Backup drills accept the exact host named by `PDS_TEST_DISPOSABLE_POSTGRES_HOST`, which the module sets to its disposable PostgreSQL service; without that explicit opt-in they still require loopback. The SQLite namespace list lives in `test/pds/sqlite_test_runner.clj`, shared with the local convenience command.

Frontend checks compare the built bundle with committed assets. Existing native multi-architecture image publishing remains in the Docker workflow.

Generated-file checks compare relative file paths and content-only hashes, ignoring filesystem ownership and permissions. Failures list the changed, added, or removed files. Engine-free regression tests cover these cases.
