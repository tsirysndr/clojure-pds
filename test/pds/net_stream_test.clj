(ns pds.net-stream-test
  (:require [clojure.test :refer [deftest is]]
            [pds.net :as net]
            [pds.net-test :as nt]
            [pds.protocol.codec :as codec]
            [pds.proxy-test :as upstream])
  (:import [java.io ByteArrayOutputStream IOException OutputStream]
           [java.security MessageDigest]
           [org.eclipse.jetty.util.ssl SslContextFactory$Client]))

(defn reply! [exchange status data chunked?]
  (.sendResponseHeaders exchange status (if chunked? 0 (alength ^bytes data)))
  (.write (.getResponseBody exchange) ^bytes data))

(deftest streamed-exchanges-bound-copy-size-and-preserve-security-and-ownership
  (upstream/with-service
    (fn [{:keys [client origin respond calls store]}]
      (let [data (byte-array (* 2 1024 1024)) _ (java.util.Arrays/fill data (byte 42))
            writes (atom []) digest (MessageDigest/getInstance "SHA-256") closed? (atom false)
            sink (proxy [OutputStream] []
                   (write [buffer offset length] (swap! writes conj length) (.update digest ^bytes buffer (int offset) (int length)))
                   (close [] (reset! closed? true)))
            url (str origin "/xrpc/com.example.read") opts {:method "POST" :body (byte-array [1 2 3]) :headers {"authorization" "Bearer replacement"}}]
        (reset! respond #(reply! % 200 data true))
        (let [result (net/exchange-to! client url opts sink)]
          (is (= 200 (:status result)))
          (is (= (alength data) (:size result)))
          (is (not (contains? result :body)))
          (is (= (vec (codec/sha256 data)) (vec (.digest digest))))
          (is (every? #(<= 1 % 65536) @writes))
          (is (false? @closed?))
          (is (= [1 2 3] (:body (last @calls))))
          (is (= "identity" (get-in (last @calls) [:headers "accept-encoding"])))
          (is (= "Bearer replacement" (get-in (last @calls) [:headers "authorization"]))))
        (let [before (count @calls)]
          (is (thrown? Exception (net/exchange-to! client url (assoc opts :headers {"cookie" "secret"}) sink)))
          (is (thrown? Exception (net/exchange-to! client (clojure.string/replace url "good.example.com" "wrong.example.com") opts sink)))
          (with-open [blocked (net/open-client {:resolver (nt/resolver "127.0.0.1")
                                                :ssl-context (doto (SslContextFactory$Client.) (.setTrustStore store))})]
            (is (thrown? Exception (net/exchange-to! blocked url opts sink))))
          (is (= before (count @calls))))))))

(deftest streamed-failures-abort-without-retry-and-never-close-the-sink
  (upstream/with-service
    (fn [{:keys [client origin respond calls]}]
      (let [url (str origin "/xrpc/com.example.write") opts {:method "POST" :body (byte-array [1]) :maximum 128}
            closed? (atom false) sink (proxy [ByteArrayOutputStream] [] (close [] (reset! closed? true)))
            one (byte-array [1])]
        (doseq [response [(fn [exchange] (reply! exchange 200 (byte-array 129) false))
                          (fn [exchange] (reply! exchange 200 (byte-array 129) true))
                          (fn [exchange] (.sendResponseHeaders exchange 200 100) (.write (.getResponseBody exchange) one))
                          (fn [exchange] (.set (.getResponseHeaders exchange) "Content-Encoding" "gzip") (reply! exchange 200 one false))]]
          (reset! respond response)
          (let [before (count @calls)]
            (.reset sink)
            (is (thrown? Exception (net/exchange-to! client url opts sink)))
            (is (<= (.size sink) 128))
            (is (= (inc before) (count @calls)))
            (is (false? @closed?))))
        (reset! respond (fn [exchange] (.set (.getResponseHeaders exchange) "Location" (str origin "/never")) (reply! exchange 307 one false)))
        (let [before (count @calls)]
          (is (= 307 (:status (net/exchange-to! client url opts sink))))
          (is (= (inc before) (count @calls))))
        (reset! respond #(reply! % 200 one false))
        (let [failure (IOException. "Caller output failed") before (count @calls)
              broken (proxy [OutputStream] [] (write [buffer offset length] (throw failure)))]
          (is (identical? failure (try (net/exchange-to! client url opts broken) (catch Exception error error))))
          (is (= (inc before) (count @calls))))
        (let [entered (promise) release (promise)]
          (reset! respond (fn [exchange]
                            (.sendResponseHeaders exchange 200 100)
                            (.write (.getResponseBody exchange) one) (.flush (.getResponseBody exchange))
                            (deliver entered true) (deref release 5000 nil)))
          (try
            (let [before (count @calls) started (System/nanoTime)]
              (is (thrown? Exception (net/exchange-to! client url (assoc opts :timeout-ms 500) sink)))
              (is (= true (deref entered 1000 :timeout)))
              (is (< (/ (- (System/nanoTime) started) 1e6) 3000))
              (is (= (inc before) (count @calls))))
            (finally (deliver release true))))
        (reset! respond (fn [exchange] (.set (.getResponseHeaders exchange) "Content-Length" "10000000") (.sendResponseHeaders exchange 200 -1)))
        (is (= 0 (:size (net/exchange-to! client url {:method "HEAD" :maximum 1} sink))))))))

(deftest streamed-request-bodies-send-a-known-length-once
  (upstream/with-service
    (fn [{:keys [client origin respond calls]}]
      (let [data (byte-array 1048576) _ (java.util.Arrays/fill data (byte 7))
            url (str origin "/xrpc/com.example.write")]
        (reset! respond #(reply! % 200 (byte-array [1]) false))
        (let [sink (ByteArrayOutputStream.)
              result (net/exchange-to! client url {:method "POST" :headers {"content-type" "application/test"}
                                                   :body {:input (java.io.ByteArrayInputStream. data) :length (alength data)}} sink)
              received (last @calls)]
          (is (= 200 (:status result)))
          (is (= [1] (vec (.toByteArray sink))))
          (is (= (vec (codec/sha256 data)) (vec (codec/sha256 (byte-array (:body received))))))
          (is (= (str (alength data)) (get-in received [:headers "content-length"])))
          (is (nil? (get-in received [:headers "transfer-encoding"])))
          (is (= "application/test" (get-in received [:headers "content-type"]))))
        (let [before (count @calls) one #(java.io.ByteArrayInputStream. (byte-array 1))]
          (doseq [body [{:input (one) :length -1} {:input (one) :length 67108865}
                        {:length 1} {:input (one)} {:input (byte-array 1) :length 1}]]
            (is (thrown? Exception (net/exchange-to! client url {:method "POST" :body body} (ByteArrayOutputStream.)))))
          (is (thrown? Exception (net/exchange! client url {:method "POST" :body {:input (one) :length 1}}))
              "The buffered exchange keeps rejecting stream bodies")
          (is (= before (count @calls))))))))
