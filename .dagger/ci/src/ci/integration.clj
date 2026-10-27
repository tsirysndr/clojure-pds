(ns ci.integration
  (:require [ci.common :as common]
            [dagger.container :as ctr]))

(defn ^{:dagger/return :string} integration
  "Run PostgreSQL, Redis, S3, backup and upstream conformance tests."
  [^:dagger/context {:keys [client]} ^{:dagger/type [:object "Directory"]} source]
  (let [postgres (-> (common/image client "postgres:18.6-bookworm")
                     (ctr/with-env-variable "POSTGRES_USER" "pds")
                     (ctr/with-env-variable "POSTGRES_PASSWORD" "pds-ci-password")
                     (ctr/with-env-variable "POSTGRES_DB" "pds_test")
                     (ctr/with-exposed-port 5432) ctr/as-service)
        redis (-> (common/image client "redis:8.2.3-alpine") (ctr/with-exposed-port 6379) ctr/as-service)]
    (-> (common/runner client source)
        (ctr/with-service-binding "postgres" postgres)
        (ctr/with-service-binding "redis" redis)
        (ctr/with-env-variable "PDS_DATABASE_USER" "pds")
        (ctr/with-env-variable "PDS_DATABASE_PASSWORD" "pds-ci-password")
        (ctr/with-env-variable "PDS_TEST_DATABASE_URL" "jdbc:postgresql://postgres:5432/pds_test")
        (ctr/with-env-variable "PDS_TEST_REDIS_URL" "redis://redis:6379/0")
        (ctr/with-env-variable "PDS_TEST_UPSTREAM" "true")
        (ctr/with-env-variable "PDS_TEST_BACKUP" "true")
        (ctr/with-env-variable "PDS_TEST_DISPOSABLE_POSTGRES_HOST" "postgres")
        (ctr/with-env-variable "PG_BIN" "/usr/lib/postgresql/18/bin")
        (ctr/with-exec ["curl" "-fsSL" "https://www.postgresql.org/media/keys/ACCC4CF8.asc" "-o" "/usr/share/keyrings/pgdg.asc"])
        (ctr/with-new-file "/etc/apt/sources.list.d/pgdg.list"
                           "deb [signed-by=/usr/share/keyrings/pgdg.asc] https://apt.postgresql.org/pub/repos/apt noble-pgdg main\n")
        (common/commands [["apt-get" "update"] ["apt-get" "install" "-y" "--no-install-recommends" "postgresql-client-18" "python3" "python3-venv"]
                   ["python3" "-m" "venv" "/opt/moto"]
                   ["/opt/moto/bin/pip" "install" "moto[server]==5.2.3"]
                   ["npm" "ci" "--prefix" "scripts/conformance" "--ignore-scripts"]
                   ["/opt/moto/bin/python" "scripts/test-s3.py"]
                   ["node" "--test" "examples/email-worker/handler.test.mjs"]])
        common/finish)))
