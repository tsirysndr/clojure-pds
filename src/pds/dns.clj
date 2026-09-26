(ns pds.dns
  (:import [java.time Duration]
           [java.util.concurrent TimeUnit]
           [org.xbill.DNS ExtendedResolver Name TXTRecord Type]
           [org.xbill.DNS.lookup LookupSession]))

(defn txt-lookup
  "Return a bounded TXT lookup function. Uses the system recursive resolvers,
  absolute names, validated DNS answer chains, and no application DNS cache."
  ([] (txt-lookup (doto (ExtendedResolver.) (.setTimeout (Duration/ofSeconds 2)) (.setRetries 1))))
  ([resolver]
   (let [session (-> (LookupSession/builder) (.resolver resolver) (.maxRedirects 4) .build)]
     (fn [name]
       (let [result (.toCompletableFuture (.lookupAsync session (Name/fromString name) Type/TXT))]
         (try
           (let [records (.getRecords (.get result 2500 TimeUnit/MILLISECONDS))]
             (when (> (count records) 128) (throw (ex-info "Too many DNS records" {})))
             ;; DNS TXT records can contain multiple length-prefixed strings.
             ;; Concatenate within each record; keep distinct records separate.
             (mapv #(apply str (.getStrings ^TXTRecord %)) (filter #(instance? TXTRecord %) records)))
           (finally (.cancel result true))))))))
