(ns pds.protocol.mst
  (:require [pds.protocol.codec :as codec]))

(defn height [key]
  (quot (loop [bytes (seq (codec/sha256 (codec/utf8 key))) zeros 0]
          (if-let [b (first bytes)]
            (let [n (bit-and b 255)]
              (if (zero? n) (recur (next bytes) (+ zeros 8))
                  (+ zeros (- (Integer/numberOfLeadingZeros n) 24)))) zeros)) 2))

(defn common-prefix [^bytes a ^bytes b]
  (loop [n 0]
    (if (and (< n (min (alength a) (alength b))) (= (aget a n) (aget b n)))
      (recur (inc n)) n)))

(defn build
  "Deterministic complete-tree builder. Returns root CID and encoded node blocks.
  Rebuilds O(n) nodes; incremental mutation is a future performance improvement."
  [records]
  (let [blocks (atom {})
        entries (mapv (fn [[k v]] {:key k :value (codec/link v) :height (height k)}) (sort records))]
    (doseq [{:keys [key]} entries]
      (when-not (and (string? key) (<= 1 (count key) 1024) (re-matches #"[\x21-\x7e]+" key))
        (codec/fail! "MST key must be nonempty printable ASCII")))
    (letfn [(store! [node]
              (let [data (codec/encode node) cid (codec/cid data)]
                (swap! blocks assoc cid data) (codec/link cid)))
            (node! [items level]
              (let [positions (vec (keep-indexed #(when (= level (:height %2)) %1) items))
                    subtree (fn [start end]
                              (when (< start end) (node! (subvec items start end) (dec level))))
                    left (subtree 0 (or (first positions) (count items)))
                    entries (mapv (fn [i index]
                                    (let [{:keys [key value]} (nth items index)
                                          prev (if (zero? i) "" (:key (nth items (nth positions (dec i)))))
                                          bytes (codec/utf8 key)
                                          prefix (common-prefix (codec/utf8 prev) bytes)]
                                      {"p" prefix "k" (java.util.Arrays/copyOfRange bytes prefix (alength bytes))
                                       "v" value "t" (subtree (inc index) (get positions (inc i) (count items)))}))
                                  (range (count positions)) positions)]
                (store! {"l" left "e" entries})))]
      (let [root (if (empty? entries) (store! {"l" nil "e" []})
                     (node! entries (apply max (map :height entries))))]
        {:root (:cid root) :blocks @blocks}))))
