(ns pds.proxy-stream-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.app :as app]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.net :as net]
            [pds.net-stream-test :as streaming]
            [pds.protocol.codec :as codec]
            [pds.proxy-api-test :as wire]
            [pds.proxy-test :as upstream]
            [pds.repo-import-test :as imports]
            [pds.response-body :as body]
            [pds.tempfile :as tempfile])
  (:import [java.io IOException InputStream]
           [java.net Socket]
           [java.net.http HttpClient]
           [java.nio.channels FileChannel]))

(use-fixtures :each fixture/isolated-database)
(def path "/xrpc/com.example.read")
(defn request [account]
  {:uri path :request-method :get :headers {"authorization" (str "Bearer " (:accessJwt account))}})
(defn consume [response]
  (with-open [stream (:body response)] (.readAllBytes ^InputStream (:input stream))))
(defn closed? [channels] (every? #(not (.isOpen ^FileChannel %)) channels))
(defn wait-closed! [channels]
  (loop [left 250]
    (when (and (pos? left) (not (closed? channels))) (Thread/sleep 20) (recur (dec left))))
  (is (closed? channels)))
(defn call-once-released
  "The permit is released just after its staging channel closes; retry the
  brief 503 window instead of racing that ordering."
  [client port method account]
  (loop [left 250]
    (let [response (wire/call client port method path (:accessJwt account) {} nil)]
      (if (and (= 503 (:status response)) (pos? left))
        (do (Thread/sleep 20) (recur (dec left)))
        response))))

(deftest prepared-response-retains-the-permit-but-no-database-connection
  (upstream/with-service
    (fn [{:keys [respond calls] :as remote}]
      (let [settings (assoc (wire/config remote) :proxy-max-concurrent 1 :proxy-max-response-bytes (* 3 1024 1024))
            account (imports/local! settings) data (byte-array (* 2 1024 1024))
            _ (java.util.Arrays/fill data (byte 37)) channels (atom []) original tempfile/open-channel!
            database {:url (.getURL fixture/*ds*) :user (.getUser fixture/*ds*) :password (.getPassword fixture/*ds*)}]
        (reset! respond #(streaming/reply! % 200 data true))
        (with-open [pool (db/open-pool! database {:maximum-size 1 :timeout-ms 500})]
          (let [handler (app/handler settings pool) req (request account)]
            (with-redefs [tempfile/open-channel! #(let [c (original)] (swap! channels conj c) c)
                          net/exchange! (fn [& _] (throw (AssertionError. "Buffered proxy exchange forbidden")))]
              (let [response (handler req)]
                (is (= 200 (:status response)))
                (is (body/stream? (:body response)))
                (is (= (alength data) (:length (:body response))))
                (is (= 503 (:status (handler req))))
                (is (= 1 (count @calls)))
                (with-open [conn (db/connection pool)]
                  (is (= 1 (:n (first (db/query conn "SELECT 1 AS n"))))))
                (is (java.util.Arrays/equals data ^bytes (consume response)))
                (.close ^java.io.Closeable (:body response)))
              (is (closed? @channels))
              (let [response (handler req)]
                (try (is (= 200 (:status response)))
                     (is (= 503 (:status (handler req))) "Double close must not release extra capacity")
                     (finally (.close ^java.io.Closeable (:body response)))))
              (is (closed? @channels)))))))))

(deftest upstream-and-local-storage-errors-release-prepared-files
  (upstream/with-service
    (fn [{:keys [respond calls] :as remote}]
      (let [settings (assoc (wire/config remote) :proxy-max-concurrent 1) account (imports/local! settings)
            handler (app/handler settings fixture/*ds*) req (request account)
            channels (atom []) original tempfile/open-channel!]
        (with-redefs [tempfile/open-channel! #(let [c (original)] (swap! channels conj c) c)]
          (doseq [reply [(fn [exchange] (streaming/reply! exchange 200 (byte-array 129) true))
                        (fn [exchange] (.sendResponseHeaders exchange 200 100) (.write (.getResponseBody exchange) (byte-array [1])))]]
            (reset! respond reply)
            (let [response (handler req)]
              (is (= 502 (:status response)))
              (is (= "UpstreamFailure" (get (json/read-str (:body response)) "error")))
              (is (closed? @channels)))))
        (reset! respond #(streaming/reply! % 200 (byte-array [1]) false))
        (with-redefs [tempfile/open-channel! #(throw (IOException. "private temp path"))]
          (let [before (count @calls) response (handler req)]
            (is (= 503 (:status response)))
            (is (= before (count @calls)))
            (is (not (.contains ^String (:body response) "private temp path")))))
        (with-redefs [tempfile/open-channel! #(let [c (original)] (.close c) c)]
          (let [before (count @calls) response (handler req)]
            (is (= 503 (:status response)))
            (is (= "ProxyUnavailable" (get (json/read-str (:body response)) "error")))
            (is (= (inc before) (count @calls)) "A local write failure must not retry the exchange")))
        (is (= [1] (vec (consume (handler req)))))))))

(deftest actual-http-delivery-head-empty-and-sanitized-error-clean-up
  (upstream/with-service
    (fn [{:keys [respond] :as remote}]
      (let [settings (assoc (wire/config remote) :proxy-max-concurrent 1 :proxy-max-response-bytes (* 3 1024 1024))
            account (imports/local! settings) data (byte-array (* 2 1024 1024))
            server (http/start! settings (app/handler settings fixture/*ds*))
            channels (atom []) original tempfile/open-channel!]
        (try
          (with-redefs [tempfile/open-channel! #(let [c (original)] (swap! channels conj c) c)]
            (with-open [client (HttpClient/newHttpClient)]
              (reset! respond #(streaming/reply! % 200 data true))
              (let [response (wire/call client (:port server) "GET" path (:accessJwt account) {} nil)]
                (is (= 200 (:status response)))
                (is (java.util.Arrays/equals data ^bytes (:raw response))))
              (wait-closed! @channels)
              (reset! respond (fn [exchange] (.set (.getResponseHeaders exchange) "Content-Length" "10000000") (.sendResponseHeaders exchange 200 -1)))
              (let [response (call-once-released client (:port server) "HEAD" account)]
                (is (= 200 (:status response)))
                (is (= "10000000" (get-in response [:headers "content-length"])))
                (is (zero? (alength ^bytes (:raw response)))))
              (reset! respond #(.sendResponseHeaders % 204 -1))
              (is (= 204 (:status (call-once-released client (:port server) "GET" account))))
              (wait-closed! @channels)
              (reset! respond #(streaming/reply! % 429 (codec/utf8 "{\"error\":\"SlowDown\",\"message\":\"Try later\"}") false))
              (is (= {"error" "SlowDown" "message" "Try later"}
                     (:body (call-once-released client (:port server) "GET" account))))
              (reset! respond #(streaming/reply! % 500 (codec/utf8 (str "{\"error\":\"LargeError\",\"message\":\"private\",\"padding\":\"" (apply str (repeat 65536 "x")) "\"}")) true))
              (let [response (call-once-released client (:port server) "GET" account)]
                (is (= 500 (:status response)))
                (is (= "UpstreamFailure" (get-in response [:body "error"])))
                (is (not (.contains ^String (String. ^bytes (:raw response) "UTF-8") "private"))))
              (wait-closed! @channels)))
          (finally ((:stop! server))))))))

(deftest downstream-disconnect-releases-staged-response-and-permit
  (upstream/with-service
    (fn [{:keys [respond] :as remote}]
      (let [settings (assoc (wire/config remote) :proxy-max-concurrent 1 :proxy-max-response-bytes (* 8 1024 1024))
            account (imports/local! settings) opened (promise) original tempfile/open-channel!
            server (http/start! settings (app/handler settings fixture/*ds*))]
        (try
          (reset! respond #(streaming/reply! % 200 (byte-array (* 8 1024 1024)) true))
          (with-redefs [tempfile/open-channel! #(let [c (original)] (deliver opened c) c)]
            (with-open [socket (Socket. "127.0.0.1" (:port server))]
              (.setSoTimeout socket 10000)
              (.write (.getOutputStream socket) (.getBytes (str "GET " path " HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " (:accessJwt account) "\r\n\r\n") "US-ASCII"))
              (.flush (.getOutputStream socket))
              (is (pos? (.read (.getInputStream socket) (byte-array 2048))))
              (.setSoLinger socket true 0))
            (let [channel (deref opened 5000 nil)]
              (is (instance? FileChannel channel))
              (when channel (wait-closed! [channel])))
            (reset! respond #(streaming/reply! % 200 (byte-array [7]) false))
            (with-open [client (HttpClient/newHttpClient)]
              (is (= 200 (:status (call-once-released client (:port server) "GET" account))))))
          (finally ((:stop! server))))))))
