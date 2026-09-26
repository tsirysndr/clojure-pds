(ns pds.protocol.syntax
  (:require [clojure.string :as str])
  (:import [java.security SecureRandom]))

(defn handle? [x]
  (boolean (and (string? x) (<= (count x) 253)
                (re-matches #"(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?" x))))

(defn did? [x]
  (boolean (and (string? x) (<= (count x) 2048)
                (re-matches #"did:[a-z]+:[a-zA-Z0-9._:%-]*[a-zA-Z0-9._-]" x))))

(defn nsid? [x]
  (boolean (and (string? x) (<= (count x) 317)
                (re-matches #"[a-zA-Z](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+\.[a-zA-Z][a-zA-Z0-9]{0,62}" x)
                (<= (count (subs x 0 (.lastIndexOf ^String x "."))) 253))))

(defn record-key? [x]
  (boolean (and (string? x) (not (#{"." ".."} x))
                (re-matches #"[A-Za-z0-9._:~-]{1,512}" x))))

(defn tid? [x]
  (boolean (and (string? x) (re-matches #"[234567abcdefghij][234567abcdefghijklmnopqrstuvwxyz]{12}" x))))

(defn at-identifier? [x] (or (did? x) (handle? x)))

(defn at-uri? [x]
  (boolean
   (and (string? x) (<= (count x) 8192) (str/starts-with? x "at://")
        (let [[authority collection rkey :as segments] (str/split (subs x 5) #"/" -1)]
          (and (<= (count segments) 3) (at-identifier? authority)
               (or (= 1 (count segments)) (nsid? collection))
               (or (< (count segments) 3) (record-key? rkey)))))))

(def alphabet "234567abcdefghijklmnopqrstuvwxyz")

(defn encode-tid [value]
  (when-not (and (integer? value) (<= 0 value Long/MAX_VALUE))
    (throw (ex-info "TID must fit in 63 bits" {})))
  (apply str (for [shift (range 60 -1 -5)]
               (.charAt alphabet (bit-and 31 (unsigned-bit-shift-right (long value) shift))))))

(defn decode-tid [value]
  (when-not (tid? value) (throw (ex-info "Invalid TID" {})))
  (reduce (fn [acc ch] (bit-or (bit-shift-left acc 5) (.indexOf alphabet (int ch)))) 0 value))

(defn tid-generator
  "Monotonic within a process; pass the persisted last revision when committing."
  ([] (tid-generator #(* 1000 (System/currentTimeMillis)) (.nextInt (SecureRandom.) 1024)))
  ([microseconds clock-id]
   (when-not (<= 0 clock-id 1023) (throw (ex-info "Invalid TID clock ID" {})))
   (let [last-value (atom 0)]
     (fn next-tid
       ([] (next-tid nil))
       ([previous]
        (encode-tid
         (swap! last-value
                #(max (inc %) (if previous (inc (decode-tid previous)) 0)
                      (+ (* 1024 (microseconds)) clock-id)))))))))
