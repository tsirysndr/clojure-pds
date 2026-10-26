(require '[dagger-main :as ci] '[dagger.core :as dag] '[dagger.modules :as modules]
         '[dagger.internal.runtime :as runtime])
(let [client (dag/with-transport (fn [_] (throw (ex-info "Unexpected network request" {}))))
      source (dag/load client "Directory" "test-source")
      requests (atom [])
      module (modules/from-ns 'dagger-main "ProjectCi")]
  (assert (= #{:frontend :integration :sqlite :nix} (set (keys (:functions module)))))
  (with-redefs [runtime/execute! (fn [object & _]
                                  (swap! requests conj (runtime/document object))
                                  "mock-id")]
    (doseq [f [ci/frontend ci/integration ci/sqlite ci/nix]]
      (assert (string? (f {:client client} source)))))
  (let [values (mapcat (comp vals :variables) @requests)]
    (assert (some #{["clojure" "-M:integration"]} values))
    (assert (some #{"jdbc:postgresql://postgres:5432/pds_test"} values))
    (assert (some #{"http://s3:5000"} values))
    (assert (some #{"LOCKED"} values)))
  (println "All Clojure handlers and nested service queries validated against the SDK schema."))
