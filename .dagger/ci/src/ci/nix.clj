(ns ci.nix
  (:require [ci.common :as common]
            [dagger.core :as dag]
            [dagger.container :as ctr]))

(defn ^{:dagger/return :string} nix
  "Build and smoke-test the Nix package and development shell."
  [^:dagger/context {:keys [client]} ^{:dagger/type [:object "Directory"]} source]
  (let [base (common/image client "nixos/nix:2.28.5")]
    (-> base
        (ctr/with-mounted-cache "/nix" (dag/cache-volume client "clojure-pds-nix-2.28.5")
                                {:source (ctr/directory base "/nix") :sharing "LOCKED"})
        (ctr/with-env-variable "NIX_REMOTE" "local")
        (ctr/with-env-variable "NIX_CONFIG" "experimental-features = nix-command flakes\nsandbox = false\nbuild-users-group =\n")
        (ctr/with-directory "/src" (common/clean source))
        (ctr/with-workdir "/src")
        (ctr/with-env-variable "PDS_SQLITE_PATH" "/tmp/pds-smoke.sqlite3")
        (common/commands [["nix" "build" ".#clojure-pds" "--print-build-logs"]
                   ["test" "-x" "result/bin/clojure-pds"]
                   ["./result/bin/clojure-pds" "migrate"]
                   ["test" "-f" "/tmp/pds-smoke.sqlite3"]
                   ["nix" "develop" "--command" "clojure" "--version"]])
        common/finish)))
