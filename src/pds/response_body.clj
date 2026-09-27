(ns pds.response-body
  "Owned, single-use HTTP bodies. The transport closes a returned body on every
  terminal path. Direct handler callers must close it themselves."
  (:import [java.io Closeable InputStream]))

(defrecord StreamBody [^InputStream input length closed?]
  Closeable
  (close [_]
    (when (compare-and-set! closed? false true)
      (.close input))))

(defn stream
  "Transfer ownership of input to a response with a known byte length. Input
  close must promptly release resources and unblock any pending read. Content
  integrity must be checked by the caller before returning sensitive content."
  [input length]
  (when-not (and (instance? InputStream input) (integer? length) (<= 0 length Long/MAX_VALUE))
    (throw (IllegalArgumentException. "A stream body requires an InputStream and a nonnegative length")))
  (->StreamBody input (long length) (atom false)))

(defn stream? [body] (instance? StreamBody body))
