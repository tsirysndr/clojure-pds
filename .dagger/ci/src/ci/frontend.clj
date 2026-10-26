(ns ci.frontend
  (:require [ci.common :as common]
            [clojure.string :as str]
            [dagger.file :as file]
            [dagger.container :as ctr]
            [dagger.directory :as dir]))

(defn- content-manifest [source]
  ;; File content hashes ignore ownership/modes introduced by container builds.
  (into (sorted-map)
        (for [path (dir/glob source "**") :when (not (str/ends-with? path "/"))]
          [path (file/digest (dir/file source path) {:exclude-metadata true})])))

(defn- ensure-unchanged [before after]
  (let [old (content-manifest before)
        new (content-manifest after)
        changed (->> (concat (keys old) (keys new)) distinct sort
                     (filter #(not= (get old %) (get new %))) vec)]
    (when (seq changed)
      (throw (ex-info (str "Regenerate and commit the account UI bundle; changed files: "
                          (str/join ", " changed)) {:changed-files changed})))))

(defn ^{:dagger/return :string} frontend
  "Test the account UI and verify that the committed bundle is current."
  [^:dagger/context {:keys [client]} ^{:dagger/type [:object "Directory"]} source]
  (let [built (-> (common/node client)
                  (ctr/with-directory "/src" (common/clean source))
                  (ctr/with-workdir "/src/frontend")
                  (common/commands [["bun" "install" "--frozen-lockfile"] ["bun" "run" "check"]
                             ["bun" "run" "test"] ["bun" "run" "build"]])
                  (ctr/with-workdir "/src")
                  (ctr/with-exec ["bun" "build" "--no-bundle" "resources/security/app.js"]))]
    (ensure-unchanged (dir/directory source "resources/security")
                      (ctr/directory built "/src/resources/security"))
    "Frontend checks passed"))
