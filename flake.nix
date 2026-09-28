{
  description = "clojure-pds, an AT Protocol Personal Data Server in Clojure";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { self, nixpkgs }:
    let
      systems = [
        "aarch64-darwin"
        "aarch64-linux"
        "x86_64-darwin"
        "x86_64-linux"
      ];

      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});

      # mise pins Temurin 25; fall back to the newest packaged JDK when a
      # matching version is not in this nixpkgs revision.
      jdkFor = pkgs: pkgs.temurin-bin-25 or pkgs.jdk25 or pkgs.jdk;

      sourceFor =
        pkgs:
        pkgs.lib.cleanSourceWith {
          name = "clojure-pds-source";
          src = self;
          filter =
            path: type:
            let
              relative = pkgs.lib.removePrefix (toString self + "/") (toString path);
            in
            builtins.any (root: relative == root || pkgs.lib.hasPrefix (root + "/") relative) [
              "deps.edn"
              "src"
              "resources"
            ];
        };
    in
    {
      packages = forAllSystems (
        pkgs:
        let
          jdk = jdkFor pkgs;
          source = sourceFor pkgs;
        in
        rec {
          # Runs the PDS from the store source. Maven/git dependencies are
          # fetched on first start into $XDG_CACHE_HOME (network required once);
          # they are not vendored into the store.
          clojure-pds = pkgs.writeShellApplication {
            name = "clojure-pds";
            runtimeInputs = [
              jdk
              pkgs.clojure
              pkgs.git
            ];
            text = ''
              cache="''${XDG_CACHE_HOME:-$HOME/.cache}/clojure-pds"
              mkdir -p "$cache/m2" "$cache/gitlibs" "$cache/cpcache"
              export GITLIBS="$cache/gitlibs"
              export CLJ_CACHE="$cache/cpcache"
              alias="''${1:-run}"
              if [ "$#" -gt 0 ]; then shift; fi
              cd ${source}
              exec clojure -Sdeps "{:mvn/local-repo \"$cache/m2\"}" -M:"$alias" "$@"
            '';
          };
          default = clojure-pds;
        }
      );

      devShells = forAllSystems (
        pkgs:
        let
          jdk = jdkFor pkgs;
        in
        {
          default = pkgs.mkShell {
            name = "clojure-pds";
            packages = [
              jdk
              pkgs.clojure
              pkgs.git
              pkgs.nodejs_24
              pkgs.postgresql_18 or pkgs.postgresql
              pkgs.python3
            ];
            env.PG_BIN = "${pkgs.postgresql_18 or pkgs.postgresql}/bin";
            shellHook = ''
              echo "clojure-pds: $(clojure --version), JDK $(java -version 2>&1 | head -1)"
            '';
          };
        }
      );

      formatter = forAllSystems (pkgs: pkgs.nixfmt-rfc-style or pkgs.nixfmt);
    };
}
