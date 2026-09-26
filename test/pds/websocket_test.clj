(ns pds.websocket-test
  (:require [clojure.test :refer [deftest is]]
            [pds.http :as http])
  (:import [java.io ByteArrayOutputStream]
           [java.net URI]
           [java.net.http HttpClient WebSocket WebSocket$Listener]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defn connect [^HttpClient client port path]
  (let [messages (LinkedBlockingQueue.) closed (promise) partial (ByteArrayOutputStream.)
        listener (reify WebSocket$Listener
                   (onOpen [_ ws] (.request ^WebSocket ws 1))
                   (onBinary [_ ws data last?]
                     (let [bytes (byte-array (.remaining ^java.nio.ByteBuffer data))]
                       (.get ^java.nio.ByteBuffer data bytes) (.write partial bytes)
                       (when last? (.put messages (.toByteArray partial)) (.reset partial)))
                     (.request ^WebSocket ws 1) nil)
                   (onPing [_ ws data] (.request ^WebSocket ws 1) (.sendPong ^WebSocket ws data))
                   (onPong [_ ws _] (.request ^WebSocket ws 1) nil)
                   (onClose [_ _ status reason] (deliver closed {:status status :reason reason}) nil)
                   (onError [_ _ error] (deliver closed {:error error})))
        ws (.get (.buildAsync (.newWebSocketBuilder client) (URI/create (str "ws://127.0.0.1:" port path)) listener) 10 TimeUnit/SECONDS)]
    {:socket ws :messages messages :closed closed}))

(defn receive [connection]
  (.poll ^LinkedBlockingQueue (:messages connection) 10 TimeUnit/SECONDS))

(deftest binary-websocket-and-shutdown
  (let [finished (promise)
        server (http/start! {:host "127.0.0.1" :port 0}
                 (fn [_] {:websocket {:on-open (fn [{:keys [send! open?]}]
                                                (try (send! (byte-array [0 1 -1]))
                                                     (while (open?) (Thread/sleep 100))
                                                     (finally (deliver finished true))))}}))]
    (try
      (with-open [client (HttpClient/newHttpClient)]
        (let [connection (connect client (:port server) "/stream")]
          (try
            (is (= [0 1 -1] (vec (receive connection))))
            ((:stop! server))
            (is (true? (deref finished 5000 false)))
            (is (map? (deref (:closed connection) 5000 nil)))
            (finally (.abort ^WebSocket (:socket connection))))))
      (finally ((:stop! server))))))
