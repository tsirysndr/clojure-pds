(ns dagger.scaffold
  "Initialize a module without overwriting existing source or project settings."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [dagger.codegen :as codegen]))

(defn initialize! [directory module-name template]
  (let [directory (io/file directory)
        path (io/file directory "deps.edn")
        config (if (.exists path) (edn/read-string (slurp path)) {})
        dependency {:local/root "dagger-sdk"}
        existing (get-in config [:deps 'personal/dagger])
        object-name (apply str (map str/capitalize (str/split (codegen/kebab module-name) #"-+")))
        source (io/file directory "src/dagger_main.clj")]
    (when-not (re-matches #"[A-Z][A-Za-z0-9]*" object-name)
      (throw (ex-info "Invalid module name" {:name module-name})))
    (when (and existing (not= existing dependency))
      (throw (ex-info "personal/dagger already refers to another dependency" {:dependency existing})))
    (io/make-parents path)
    (spit path (with-out-str
                 (pprint/pprint (-> config
                                   (update :paths #(vec (distinct (cons "src" %))))
                                   (assoc-in [:deps 'personal/dagger] dependency)))))
    (when-not (.exists source)
      (io/make-parents source)
      (spit source (str/replace (slurp template) "__OBJECT__" object-name)))))

(defn -main [directory name template]
  (initialize! directory name template))
