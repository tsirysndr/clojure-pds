(ns pds.dns-test
  (:require [clojure.test :refer [deftest is]]
            [pds.dns :as dns])
  (:import [java.net DatagramPacket DatagramSocket InetSocketAddress SocketException]
           [java.time Duration]
           [java.util Arrays]
           [org.xbill.DNS CNAMERecord DClass Flags Message Name Rcode Section SimpleResolver TXTRecord]))

(deftest wire-txt-chunks-cname-and-unrelated-records
  (with-open [socket (DatagramSocket. (InetSocketAddress. "127.0.0.1" 0))]
    (let [seen (atom [])
          worker (Thread/startVirtualThread
                  (fn []
                    (try
                      (while (not (.isClosed socket))
                        (let [packet (DatagramPacket. (byte-array 4096) 4096)]
                          (.receive socket packet)
                          (let [query (Message. (Arrays/copyOf (.getData packet) (.getLength packet)))
                                question (.getQuestion query) name (.getName question)
                                reply (Message. (.getID (.getHeader query)))
                                alias (Name/fromString "alias.example.com.")]
                            (swap! seen conj (str name))
                            (.setFlag (.getHeader reply) Flags/QR)
                            (.setFlag (.getHeader reply) Flags/RA)
                            (.addRecord reply question Section/QUESTION)
                            (case (str name)
                              "_atproto.alice.example.com." (.addRecord reply (CNAMERecord. name DClass/IN 60 alias) Section/ANSWER)
                              "alias.example.com." (do (.addRecord reply (TXTRecord. alias DClass/IN 60 ^java.util.List ["did=did:web:" "alice.example.com"]) Section/ANSWER)
                                                       (.addRecord reply (TXTRecord. (Name/fromString "unrelated.example.com.") DClass/IN 60 "did=did:web:attacker.example.com") Section/ANSWER))
                              (.setRcode (.getHeader reply) Rcode/NXDOMAIN))
                            (let [wire (.toWire reply)] (.send socket (DatagramPacket. wire (alength wire) (.getSocketAddress packet)))))))
                      (catch SocketException e (when-not (.isClosed socket) (throw e))))))
          lookup (dns/txt-lookup (doto (SimpleResolver. (InetSocketAddress. "127.0.0.1" (.getLocalPort socket)))
                                  (.setTimeout (Duration/ofMillis 500))))]
      (try
        (is (= ["did=did:web:alice.example.com"] (lookup "_atproto.alice.example.com.")))
        (is (= ["_atproto.alice.example.com." "alias.example.com."] @seen))
        (is (thrown? Exception (lookup "missing.example.com.")))
        (finally (.close socket) (.join worker 1000))))))
