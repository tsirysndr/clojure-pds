(ns pds.oauth.parameters
  "Bounded, strict UTF-8 form/query decoding for OAuth parameters."
  (:require [clojure.string :as str]
            [pds.protocol.codec :as codec])
  (:import [java.io ByteArrayOutputStream]))

(defn invalid! [] (throw (ex-info "Invalid OAuth parameters" {:oauth-error "invalid_request"})))
(defn- decode! [value]
  (with-open [out (ByteArrayOutputStream.)]
    (loop [i 0]
      (when (< i (count value))
        (let [ch (.charAt ^String value i)]
          (cond
            (= ch \%) (do (when (> (+ i 3) (count value)) (invalid!))
                           (let [hex (subs value (inc i) (+ i 3))]
                             (when-not (re-matches #"[0-9a-fA-F]{2}" hex) (invalid!))
                             (.write out (Integer/parseInt hex 16)))
                           (recur (+ i 3)))
            (= ch \+) (do (.write out 32) (recur (inc i)))
            (<= 33 (int ch) 126) (do (.write out (int ch)) (recur (inc i)))
            :else (invalid!)))))
    (codec/text (.toByteArray out))))

(defn parse!
  ([text] (parse! text #{}))
  ([text array-keys]
   (try
     (when-not (and (string? text) (<= (count text) 16384)) (invalid!))
     (let [pairs (if (empty? text) [] (str/split text #"&" -1))]
       (when (> (count pairs) 64) (invalid!))
       (reduce (fn [out pair]
                 (let [[k v] (str/split pair #"=" 2) k (decode! k) v (decode! (or v ""))]
                   (when (or (empty? k) (and (contains? out k) (not (array-keys k)))) (invalid!))
                   (if (array-keys k) (update out k (fnil conj []) v) (assoc out k v))))
               {} pairs))
     (catch Exception _ (invalid!)))))
