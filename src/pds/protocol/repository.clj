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

(defn verify-car
  "Verify a complete version-3 repository against an explicitly trusted DID
  and signing key. The first CAR root is the commit; unrelated blocks, previous
  commits, blobs and links inside records are not followed or returned as owned.
  Historical records are checked as data objects, not against today's Lexicons."
  ([bytes did signing-key] (verify-car bytes did signing-key (System/currentTimeMillis)))
  ([bytes did {:keys [algorithm public]} now-ms]
   (let [{:keys [roots blocks]} (car/decode bytes)
         head (first roots) content (get blocks head)]
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
       (let [tree (mst/read-tree (:cid (get commit "data")) blocks)
             paths (mapv (fn [[path cid]]
                           (let [[collection rkey :as parts] (str/split path #"/" -1)]
                             (when-not (and (= 2 (count parts)) (syntax/nsid? collection) (syntax/record-key? rkey))
                               (codec/fail! "Invalid repository record path"))
                             {:collection collection :rkey rkey :cid cid})) (sort (:records tree)))
             records (into {} (map (fn [cid]
                                    (let [data (get blocks cid)]
                                      (when-not (and data (= cid (codec/cid data))) (codec/fail! "Missing or corrupt repository record"))
                                      (let [value (codec/decode data 1000000)]
                                        (when-not (and (map? value) (not (instance? pds.protocol.codec.Link value)))
                                          (codec/fail! "Repository record must be an object"))
                                        [cid value])))) (distinct (vals (:records tree))))]
         {:head head :rev rev :commit commit :root (:root tree) :paths paths :records records
          :blocks (merge {head content} (:blocks tree) (select-keys blocks (keys records)))})))))
