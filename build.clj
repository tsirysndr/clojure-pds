(ns build
  "Builds the standalone jar: java -jar target/clojure-pds.jar starts the server,
  and the admin tools run with java -cp target/clojure-pds.jar clojure.main -m
  pds.account-admin (or any other -main namespace)."
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def jar-file "target/clojure-pds.jar")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_] (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["resources"] :target-dir class-dir})
  ;; Every namespace is compiled ahead of time; Clojure emits Java 8 bytecode,
  ;; so the jar runs on any JDK 21+ whichever JDK built it.
  (b/compile-clj {:basis @basis :src-dirs ["src"] :class-dir class-dir
                  :java-opts ["-Dclojure.compiler.direct-linking=true"]})
  (b/uber {:class-dir class-dir :uber-file jar-file :basis @basis :main 'pds.main
           ;; Signed dependencies would otherwise leave stale signature files that
           ;; make the JVM reject the merged jar.
           :exclude ["META-INF/.*\\.SF" "META-INF/.*\\.DSA" "META-INF/.*\\.RSA" "META-INF/.*\\.EC"]})
  (println "built" jar-file))
