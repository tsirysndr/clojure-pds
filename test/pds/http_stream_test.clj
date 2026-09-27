(ns pds.http-stream-test
  (:require [clojure.test :refer [deftest is testing]]
            [pds.http :as http]
            [pds.http-test :as ht]
            [pds.response-body :as body])
  (:import [java.io ByteArrayInputStream IOException InputStream]
           [java.net Socket InetSocketAddress]
           [java.net.http HttpClient]
           [java.util.concurrent CountDownLatch TimeUnit]))

(def settings {:host "127.0.0.1" :port 0})

(defn tracked [data]
  (let [reads (atom 0) closed (promise) closes (atom 0)
        stream (proxy [ByteArrayInputStream] [data]
                 (read
                   ([] (swap! reads inc) (proxy-super read))
                   ([buffer offset length] (swap! reads inc) (proxy-super read buffer offset length)))
                 (close [] (swap! closes inc) (deliver closed true) (proxy-super close)))]
    {:stream stream :reads reads :closed closed :closes closes}))

(deftest owned-stream-round-trips-and-head-closes-without-reading
  (doseq [[method status text] [["GET" 200 "streamed content"] ["GET" 200 ""]
                               ["GET" 200 (apply str (repeat 20000 "0123456789abcdef"))]
                               ["HEAD" 200 "not sent"] ["GET" 204 "not sent"] ["GET" 304 "not sent"]]]
    (testing (str method " " status " " (count text))
      (let [{:keys [stream reads closed closes]} (tracked (.getBytes text "UTF-8"))
            response-body (body/stream stream (count text))
            server (http/start! settings (constantly {:status status :headers {"Content-Type" "application/octet-stream"}
                                                     :body response-body}))]
        (try
          (with-open [client (HttpClient/newHttpClient)]
            (let [response (ht/request client (:port server) method "/")]
              (is (= status (.statusCode response)))
              (is (= (if (and (= method "GET") (= status 200)) text "") (.body response)))
              (when (= status 200)
                (is (= (str (count text)) (.orElse (.firstValue (.headers response) "content-length") nil)))))
            (is (= true (deref closed 3000 :timeout)))
            (when (or (= method "HEAD") (#{204 304} status)) (is (zero? @reads))))
          (finally ((:stop! server)) (.close response-body)))
        (is (= 1 @closes))))))

(deftest invalid-lengths-and-read-errors-do-not-complete-successfully
  (doseq [[text size] [["short" 20] ["oversized" 3] ["x" 0]
                     [(apply str (repeat 150000 "x")) 300000]
                     [(apply str (repeat 150000 "x")) 130000]]]
    (let [{:keys [stream closed closes]} (tracked (.getBytes text "UTF-8"))
          server (http/start! settings (constantly {:status 200 :body (body/stream stream size)}))]
      (try
        (with-open [client (HttpClient/newHttpClient)]
          (is (try (not= 200 (.statusCode (ht/request client (:port server) "GET" "/")))
                   (catch IOException _ true))))
        (is (= true (deref closed 3000 :timeout)))
        (finally ((:stop! server))))
      (is (= 1 @closes))))
  (let [closed (promise)
        stream (proxy [InputStream] []
                 (read
                   ([] (throw (IOException. "read failed")))
                   ([buffer offset length] (throw (IOException. "read failed"))))
                 (close [] (deliver closed true)))
        server (http/start! settings (constantly {:status 200 :body (body/stream stream 10)}))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (is (try (not= 200 (.statusCode (ht/request client (:port server) "GET" "/")))
                 (catch IOException _ true))))
      (is (= true (deref closed 3000 :timeout)))
      (finally ((:stop! server))))))

(defn socket-request [port]
  (let [socket (Socket.)]
    (.setReceiveBufferSize socket 1024)
    (.setSoTimeout socket 5000)
    (.connect socket (InetSocketAddress. "127.0.0.1" port))
    (.write (.getOutputStream socket) (.getBytes "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n" "US-ASCII"))
    (.flush (.getOutputStream socket))
    socket))

(defn read-headers [^Socket socket]
  (let [out (StringBuilder.) input (.getInputStream socket)]
    (loop []
      (let [b (.read input)]
        (when (= -1 b) (throw (IOException. "No response headers")))
        (.append out (char b))
        (when (> (.length out) 8192) (throw (IOException. "Excessive headers")))
        (if (.endsWith (str out) "\r\n\r\n") (str out) (recur))))))

(deftest slow-client-applies-backpressure-and-disconnect-releases-source
  (let [size (* 256 1024 1024) consumed (atom 0) largest (atom 0) closed (promise) closes (atom 0)
        stream (proxy [InputStream] []
                 (read
                   ([] (throw (IOException. "Unexpected EOF probe")))
                   ([buffer offset length]
                    (swap! largest max length)
                    (swap! consumed + length)
                    (java.util.Arrays/fill ^bytes buffer (int offset) (int (+ offset length)) (byte 7))
                    length))
                 (close [] (swap! closes inc) (deliver closed true)))
        server (http/start! settings (constantly {:status 200 :body (body/stream stream size)}))]
    (try
      (with-open [socket (socket-request (:port server))]
        (is (.startsWith (read-headers socket) "HTTP/1.1 200"))
        ;; Leave the receive window full. A buffering adapter would consume all
        ;; 256 MiB; the transport must stop reading while a write is pending.
        (Thread/sleep 300)
        (is (pos? @consumed))
        (is (< @consumed (* 16 1024 1024)))
        (is (<= @largest 65536))
        (.setSoLinger socket true 0))
      (is (= true (deref closed 5000 :timeout)))
      (finally ((:stop! server))))
    (is (= 1 @closes))))

(deftest shutdown-closes-and-unblocks-an-active-source
  (let [reading (promise) closed (promise) exited (promise) closes (atom 0) latch (CountDownLatch. 1)
        stream (proxy [InputStream] []
                 (read
                   ([] -1)
                   ([buffer offset length]
                    (deliver reading true)
                    (try (.await latch 10 TimeUnit/SECONDS) -1
                         (finally (deliver exited true)))))
                 (close [] (swap! closes inc) (.countDown latch) (deliver closed true)))
        server (http/start! settings (constantly {:status 200 :body (body/stream stream 100)}))]
    (try
      (with-open [socket (socket-request (:port server))]
        (is (= true (deref reading 3000 :timeout)))
        ((:stop! server))
        (is (= true (deref closed 1000 :timeout)))
        (is (= true (deref exited 1000 :timeout))))
      (finally ((:stop! server))))
    (is (= 1 @closes))))
