(ns pds.tempfile
  "Private, owned temporary storage for bounded transfers."
  (:import [java.io InputStream IOException]
           [java.nio ByteBuffer]
           [java.nio.channels FileChannel]
           [java.nio.file Files StandardOpenOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(defn open-channel! ^FileChannel []
  (let [path (Files/createTempFile "pds-blob-" ".tmp"
               (into-array FileAttribute [(PosixFilePermissions/asFileAttribute
                                            (PosixFilePermissions/fromString "rw-------"))]))]
    (try
      (FileChannel/open path (into-array StandardOpenOption
                              [StandardOpenOption/READ StandardOpenOption/WRITE StandardOpenOption/DELETE_ON_CLOSE]))
      (catch Throwable error (Files/deleteIfExists path) (throw error)))))

(defn input
  "An independent reader starting at offset zero. Closing a reader does not
  close the owner's channel. This supports repeatable SDK signing/retry reads."
  ^InputStream [^FileChannel channel]
  (let [position (atom 0) closed? (atom false)]
    (proxy [InputStream] []
      (read
        ([] (let [buffer (byte-array 1) n (.read ^InputStream this buffer 0 1)]
              (if (= -1 n) -1 (bit-and 255 (aget buffer 0)))))
        ([buffer offset length]
         (when @closed? (throw (IOException. "Reader is closed")))
         (if (zero? length) 0
             (let [n (.read channel (ByteBuffer/wrap buffer offset length) (long @position))]
               (when (pos? n) (swap! position + n)) n))))
      (close [] (reset! closed? true)))))
