(ns pds.protocol.repository
  (:require [clojure.string :as str]
            [pds.crypto :as crypto]
            [pds.protocol.car :as car]
            [pds.protocol.codec :as codec]
            [pds.protocol.mst :as mst]
            [pds.protocol.syntax :as syntax]))

(defn- cbor-link? [value]
  (and (instance? pds.protocol.codec.Link value)
       (= 113 (aget (codec/cid-bytes (:cid value)) 1))))

(defn- verified-commit [{:keys [roots blocks]} did {:keys [algorithm public]} now-ms]
  (let [head (first roots) content (blocks head)]
     (when-not (and content (= head (codec/cid content))) (codec/fail! "CAR must contain a CBOR commit root"))
     (let [commit (codec/decode content)
           rev (get commit "rev") signature (get commit "sig")]
       (when-not (and (map? commit) (= #{"did" "version" "data" "rev" "prev" "sig"} (set (keys commit)))
                      (syntax/did? did) (= did (get commit "did")) (= 3 (get commit "version"))
                      (syntax/tid? rev) (cbor-link? (get commit "data"))
                      (or (nil? (get commit "prev")) (cbor-link? (get commit "prev")))
                      (bytes? signature) (= 64 (alength ^bytes signature)))
         (codec/fail! "Invalid repository commit"))
       (when (> (unsigned-bit-shift-right (syntax/decode-tid rev) 10) (* 1000 (+ now-ms 300000)))
         (codec/fail! "Repository revision is over five minutes in the future"))
       (when-not (crypto/verify algorithm public (codec/encode (dissoc commit "sig")) signature)
         (codec/fail! "Invalid repository signature"))
       {:head head :rev rev :commit commit :content content :root (:cid (get commit "data"))})))

(defn- verify-blocks* [roots load-block did signing-key now-ms visit-record!]
  (let [{:keys [head rev commit root]} (verified-commit {:roots roots :blocks load-block} did signing-key now-ms)
        tree (mst/visit-tree! root load-block (fn [_ _]))
        paths (mapv (fn [[path cid]]
                      (let [[collection rkey :as parts] (str/split path #"/" -1)]
                        (when-not (and (= 2 (count parts)) (syntax/nsid? collection) (syntax/record-key? rkey))
                          (codec/fail! "Invalid repository record path"))
                        {:collection collection :rkey rkey :cid cid})) (sort (:records tree)))
        record-cids (set (vals (:records tree)))]
    (doseq [cid record-cids]
      (let [data (load-block cid)]
        (when-not (and data (= cid (codec/cid data))) (codec/fail! "Missing or corrupt repository record"))
        (let [value (codec/decode data 1000000)]
          (when-not (and (map? value) (not (instance? pds.protocol.codec.Link value)))
            (codec/fail! "Repository record must be an object"))
          (visit-record! cid value))))
    {:head head :rev rev :commit commit :root root :paths paths
     :block-cids (into (conj (:nodes tree) head) record-cids)}))

(defn verify-blocks
  "Verify a complete v3 repository from CAR roots and an immutable block loader.
  Returns only commit/path/CID metadata. Loads and checks record values individually,
  without retaining their payloads. Unrelated blocks and arbitrary record links
  never become owned. The caller must verify the entire CAR framing/hashes first."
  ([roots load-block did signing-key]
   (verify-blocks roots load-block did signing-key (System/currentTimeMillis)))
  ([roots load-block did signing-key now-ms]
   (verify-blocks* roots load-block did signing-key now-ms (fn [_ _]))))

(defn verify-car
  "Buffered complete-repository verification for callers needing block and record
  maps. Uses the same canonical tree, path, identity and signature checks as the
  streaming importer; historical objects do not require today's Lexicons."
  ([bytes did signing-key] (verify-car bytes did signing-key (System/currentTimeMillis)))
  ([bytes did signing-key now-ms]
   (let [{:keys [roots blocks]} (car/decode bytes) records (atom {})
         verified (verify-blocks* roots blocks did signing-key now-ms #(swap! records assoc %1 %2))]
     (-> verified (dissoc :block-cids)
         (assoc :records @records :blocks (select-keys blocks (:block-cids verified)))))))

(defn verify-record
  "Authenticate a single record's inclusion/absence using a partial CAR. The
  caller supplies the trusted DID/key and exact path; unconnected blocks cannot
  satisfy a claim. Returns nil :record/:cid for proven absence, throws for an
  incomplete proof. Does not claim that unvisited tree branches are canonical."
  ([bytes did signing-key collection rkey]
   (verify-record bytes did signing-key collection rkey (System/currentTimeMillis)))
  ([bytes did signing-key collection rkey now-ms]
   (when-not (and (syntax/nsid? collection) (syntax/record-key? rkey))
     (codec/fail! "Invalid repository record path"))
   (let [decoded (car/decode bytes)
         {:keys [head rev root]} (verified-commit decoded did signing-key now-ms)
         {:keys [cid record]} (mst/verify-proof root (str collection "/" rkey) (:blocks decoded))]
     {:head head :rev rev :cid cid :record record})))
