(ns dagger-main
  "Portable CI: lazy containers, disposable services, and ordinary Clojure functions."
  (:require [dagger.core :as dag]
            [dagger.container :as ctr]
            [dagger.directory :as dir]
            [dagger.modules :as modules]))

(defn- image [client address] (ctr/from (dag/container client) address))
(defn- commands [container argv] (reduce ctr/with-exec container argv))
(defn- clean [source]
  (reduce dir/without-directory source
          [".git" ".dagger" ".cpcache" ".gitlibs" "target" "node_modules"
           "frontend/node_modules" "scripts/conformance/node_modules"]))
(defn- finish [container] (ctr/sync container) "Checks passed")
(defn- node [client]
  (-> (image client "node:24-bookworm-slim")
      (ctr/with-file "/usr/local/bin/bun"
                     (ctr/file (image client "oven/bun:1.4.2") "/usr/local/bin/bun"))))

(defn ^{:dagger/return :string} frontend
  "Test the account UI and verify that the committed bundle is current."
  [^:dagger/context {:keys [client]} ^{:dagger/type [:object "Directory"]} source]
  (let [built (-> (node client)
                  (ctr/with-directory "/src" (clean source))
                  (ctr/with-workdir "/src/frontend")
                  (commands [["bun" "install" "--frozen-lockfile"] ["bun" "run" "check"]
                             ["bun" "run" "test"] ["bun" "run" "build"]])
                  (ctr/with-workdir "/src")
                  (ctr/with-exec ["bun" "build" "--no-bundle" "resources/security/app.js"]))]
    (when-not (= (dir/digest (dir/directory source "resources/security"))
                 (dir/digest (ctr/directory built "/src/resources/security")))
      (throw (ex-info "Regenerate and commit the account UI bundle" {})))
    "Frontend checks passed"))

(defn- runner [client source]
  ;; The existing builder pins JDK 25 and Clojure CLI 1.12.4.1618.
  (-> (dir/docker-build (clean source) {:target "builder"})
      (ctr/with-directory "/src" (clean source))
      (ctr/with-directory "/usr/local" (ctr/directory (node client) "/usr/local"))
      (ctr/with-mounted-cache "/root/.m2" (dag/cache-volume client "clojure-pds-maven"))
      (ctr/with-workdir "/src")
      (ctr/with-env-variable "GITLIBS" "/root/.gitlibs")
      (ctr/with-env-variable "CLJ_CACHE" "/src/.cpcache")))

(defn ^{:dagger/return :string} integration
  "Run PostgreSQL, Redis, S3, backup and upstream conformance tests."
  [^:dagger/context {:keys [client]} ^{:dagger/type [:object "Directory"]} source]
  (let [postgres (-> (image client "postgres:18.6-bookworm")
                     (ctr/with-env-variable "POSTGRES_USER" "pds")
                     (ctr/with-env-variable "POSTGRES_PASSWORD" "pds-ci-password")
                     (ctr/with-env-variable "POSTGRES_DB" "pds_test")
                     (ctr/with-exposed-port 5432) ctr/as-service)
        redis (-> (image client "redis:8.2.3-alpine") (ctr/with-exposed-port 6379) ctr/as-service)
        s3 (-> (image client "python:3.13-slim-bookworm")
               (ctr/with-exec ["pip" "install" "--no-cache-dir" "moto[server]==5.2.3"])
               (ctr/with-default-args ["moto_server" "-H" "0.0.0.0" "-p" "5000"])
               (ctr/with-exposed-port 5000) ctr/as-service)]
    (-> (runner client source)
        (ctr/with-service-binding "postgres" postgres)
        (ctr/with-service-binding "redis" redis)
        (ctr/with-service-binding "s3" s3)
        (ctr/with-env-variable "PDS_DATABASE_USER" "pds")
        (ctr/with-env-variable "PDS_DATABASE_PASSWORD" "pds-ci-password")
        (ctr/with-env-variable "PDS_TEST_DATABASE_URL" "jdbc:postgresql://postgres:5432/pds_test")
        (ctr/with-env-variable "PDS_TEST_REDIS_URL" "redis://redis:6379/0")
        (ctr/with-env-variable "PDS_TEST_S3_ENDPOINT" "http://s3:5000")
        (ctr/with-env-variable "PDS_TEST_UPSTREAM" "true")
        (ctr/with-env-variable "PDS_TEST_BACKUP" "true")
        (ctr/with-env-variable "PDS_TEST_DISPOSABLE_POSTGRES_HOST" "postgres")
        (ctr/with-env-variable "PG_BIN" "/usr/lib/postgresql/18/bin")
        (ctr/with-exec ["curl" "-fsSL" "https://www.postgresql.org/media/keys/ACCC4CF8.asc" "-o" "/usr/share/keyrings/pgdg.asc"])
        (ctr/with-new-file "/etc/apt/sources.list.d/pgdg.list"
                           "deb [signed-by=/usr/share/keyrings/pgdg.asc] https://apt.postgresql.org/pub/repos/apt noble-pgdg main\n")
        (commands [["apt-get" "update"] ["apt-get" "install" "-y" "--no-install-recommends" "postgresql-client-18" "python3"]
                   ["npm" "ci" "--prefix" "scripts/conformance" "--ignore-scripts"]
                   ["clojure" "-M:integration"]
                   ["node" "--test" "examples/email-worker/handler.test.mjs"]])
        finish)))

(defn ^{:dagger/return :string} sqlite
  "Run the explicitly supported SQLite integration namespaces."
  [^:dagger/context {:keys [client]} ^{:dagger/type [:object "Directory"]} source]
  (-> (runner client source)
      (ctr/with-env-variable "PDS_TEST_DATABASE_URL" "jdbc:sqlite:")
      (ctr/with-exec ["clojure" "-Sdeps" "{:aliases {:sqlite-tests {:extra-paths [\"test\" \"test-integration\"]}}}"
                      "-M:sqlite-tests" "-m" "pds.sqlite-test-runner"])
      finish))

(defn ^{:dagger/return :string} nix
  "Build and smoke-test the Nix package and development shell."
  [^:dagger/context {:keys [client]} ^{:dagger/type [:object "Directory"]} source]
  (let [base (image client "nixos/nix:2.28.5")]
    (-> base
        (ctr/with-mounted-cache "/nix" (dag/cache-volume client "clojure-pds-nix-2.28.5")
                                {:source (ctr/directory base "/nix") :sharing "LOCKED"})
        (ctr/with-env-variable "NIX_REMOTE" "local")
        (ctr/with-env-variable "NIX_CONFIG" "experimental-features = nix-command flakes\nsandbox = false\nbuild-users-group =\n")
        (ctr/with-directory "/src" (clean source))
        (ctr/with-workdir "/src")
        (ctr/with-env-variable "PDS_SQLITE_PATH" "/tmp/pds-smoke.sqlite3")
        (commands [["nix" "build" ".#clojure-pds" "--print-build-logs"]
                   ["test" "-x" "result/bin/clojure-pds"]
                   ["./result/bin/clojure-pds" "migrate"]
                   ["test" "-f" "/tmp/pds-smoke.sqlite3"]
                   ["nix" "develop" "--command" "clojure" "--version"]])
        finish)))

(defn -main [& _]
  ;; Compilation and Nix builds can legitimately exceed the SDK default of five minutes.
  (with-open [client (dag/connect {:timeout-ms 3600000})]
    (modules/serve-with-client! client (modules/from-ns 'dagger-main "ProjectCi"))))
