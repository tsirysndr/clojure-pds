(require '[ci.pipeline :as pipeline] '[ci.frontend :as frontend]
         '[dagger.directory :as dir] '[dagger.file :as file] '[dagger.core :as dag] '[dagger.modules :as modules]
         '[dagger.internal.runtime :as runtime])
(let [client (dag/with-transport (fn [_] (throw (ex-info "Unexpected network request" {}))))
      source (dag/load client "Directory" "test-source")
      requests (atom [])
      module (pipeline/definition)]
  (assert (= #{:frontend :integration :sqlite :nix} (set (keys (:functions module)))))
  (with-redefs [runtime/execute! (fn [object & _]
                                  (swap! requests conj (runtime/document object))
                                  (if (= "glob" (:name (last (:selection object))))
                                    ["app.js" "assets/" "assets/style.css"]
                                    "mock-id"))]
    (doseq [name ["frontend" "integration" "sqlite" "nix"]]
      (assert (string? (modules/invoke! module client "ProjectCi" name {} {"source" "test-source"})))))
  (let [values (mapcat (comp vals :variables) @requests)]
    (assert (some #{["clojure" "-M:integration"]} values))
    (assert (some #{"jdbc:postgresql://postgres:5432/pds_test"} values))
    (assert (some #{"http://s3:5000"} values))
    (assert (some #{"LOCKED"} values))
    (assert (some true? values) "content hashes must exclude metadata"))
  (println "All Clojure handlers and nested service queries validated against the SDK schema."))

;; Model different directory metadata while insisting on content-only hashes.
(defn compare-assets [before after]
  (with-redefs [dir/glob (fn [source _] (conj (vec (keys source)) "empty/"))
                dir/file (fn [source path] [source path])
                file/digest (fn [[source path] options]
                              (assert (= {:exclude-metadata true} options))
                              (get source path))]
    (try
      (#'frontend/ensure-unchanged before after)
      []
      (catch clojure.lang.ExceptionInfo error
        (:changed-files (ex-data error))))))

(let [original {"app.js" "javascript" "assets/style.css" "css"}]
  (assert (= [] (compare-assets original original)))
  (assert (= ["app.js"] (compare-assets original (assoc original "app.js" "changed"))))
  (assert (= ["assets/style.css"] (compare-assets original (dissoc original "assets/style.css"))))
  (assert (= ["assets/new.css"] (compare-assets original (assoc original "assets/new.css" "new")))))
(println "Content comparison detects changed, added, and removed files and ignores metadata.")
