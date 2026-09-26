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

(defn proof
  "Read only the search path for key. Includes the record block when present;
  the same path proves absence when no matching key exists. load-block returns
  bytes by CID. Traversal never follows arbitrary links inside record values."
  ([root key load-block] (proof root key load-block true))
  ([root key load-block include-record?]
  (letfn [(load! [cid]
            (let [data (load-block cid)]
              (when-not (and data (= cid (codec/cid data))) (codec/fail! "Missing or corrupt MST proof block"))
              data))
          (locate [node]
            (loop [entries (get node "e") previous "" child (get node "l")]
              (if-let [entry (first entries)]
                (let [prefix (get entry "p")
                      _ (when-not (and (integer? prefix) (<= 0 prefix (count previous)))
                          (codec/fail! "Invalid MST prefix"))
                      entry-key (str (subs previous 0 prefix) (codec/text (get entry "k")))
                      order (compare key entry-key)]
                  (cond (zero? order) [:record (:cid (get entry "v"))]
                        (neg? order) [:child (:cid child)]
                        :else (recur (next entries) entry-key (get entry "t"))))
                [:child (:cid child)])))]
    (loop [cid root blocks {}]
      (when (or (contains? blocks cid) (>= (count blocks) 128)) (codec/fail! "Invalid MST traversal"))
      (let [data (load! cid) blocks (assoc blocks cid data)
            [kind next-cid] (locate (codec/decode data))]
        (cond (= kind :record) {:cid next-cid :blocks (if include-record? (assoc blocks next-cid (load! next-cid)) blocks)}
              next-cid (recur next-cid blocks)
              :else {:cid nil :blocks blocks}))))))

(defn covering-proof
  "MST nodes for the target plus its immediate neighboring leaves. Including
  both boundaries lets consumers invert insertions/deletions on a partial tree."
  [tree paths]
  (let [ordered (into (sorted-set) (keys (:records tree)))
        targets (distinct (mapcat (fn [path]
                                   (remove nil? [path (first (rsubseq ordered < path))
                                                 (first (subseq ordered > path))])) paths))]
    (reduce (fn [blocks path] (merge blocks (:blocks (proof (:root tree) path (:blocks tree) false))))
            {(:root tree) (get (:blocks tree) (:root tree))} targets)))

(defn read-tree
  "Validate an untrusted complete MST. Only traverses tree links, returning
  path/CID mappings and reachable tree blocks. Rebuilding checks canonical
  heights, intermediate nodes and maximal prefix compression as well as shape."
  [root load-block]
  (let [blocks (atom {}) records (atom {})]
    (letfn [(link! [value nullable?]
              (when-not (or (and nullable? (nil? value))
                            (and (instance? pds.protocol.codec.Link value)
                                 (= 113 (aget (codec/cid-bytes (:cid value)) 1))))
                (codec/fail! "MST requires a CBOR CID link"))
              (:cid value))
            (walk! [cid lower upper depth]
              (when (or (> depth 128) (contains? @blocks cid))
                (codec/fail! "Repeated or excessively deep MST node"))
              (let [data (load-block cid)]
                (when-not (and data (= cid (codec/cid data))) (codec/fail! "Missing or corrupt MST node"))
                (swap! blocks assoc cid data)
                (let [node (codec/decode data)]
                  (when-not (and (map? node) (= #{"l" "e"} (set (keys node))) (vector? (get node "e")))
                    (codec/fail! "Invalid MST node"))
                  (let [entries
                        (loop [pending (seq (get node "e")) previous "" result []]
                          (if-let [entry (first pending)]
                            (let [prefix (get entry "p") suffix (get entry "k")]
                              (when-not (and (map? entry) (= #{"p" "k" "v" "t"} (set (keys entry)))
                                             (integer? prefix) (<= 0 prefix (count previous)) (bytes? suffix)
                                             (<= 1 (+ prefix (alength ^bytes suffix)) 1024))
                                (codec/fail! "Invalid MST entry"))
                              (let [key (str (subs previous 0 prefix) (codec/text suffix))]
                                (when-not (and (re-matches #"[\x21-\x7e]+" key)
                                               (pos? (compare key previous))
                                               (or (nil? lower) (pos? (compare key lower)))
                                               (or (nil? upper) (neg? (compare key upper)))
                                               (not (contains? @records key)))
                                  (codec/fail! "Invalid or unordered MST key"))
                                (swap! records assoc key (link! (get entry "v") false))
                                (recur (next pending) key (conj result [key (link! (get entry "t") true)]))))
                            result))]
                    (when-let [left (link! (get node "l") true)]
                      (walk! left lower (or (ffirst entries) upper) (inc depth)))
                    (doseq [[index [key child]] (map-indexed vector entries)]
                      (when child (walk! child key (or (first (get entries (inc index))) upper) (inc depth))))))))]
      (walk! root nil nil 0)
      (when-not (= root (:root (build @records)))
        (codec/fail! "MST is not the canonical tree for its records"))
      {:root root :records @records :blocks @blocks})))
