(ns pds.protocol.formats
  (:refer-clojure :exclude [uri?])
  (:require [clojure.string :as str]
            [pds.protocol.codec :as codec]
            [pds.protocol.syntax :as syntax])
  (:import [java.net URI]
           [java.time LocalDateTime ZoneOffset]
           [java.util Locale$Builder]))

(defn datetime? [value]
  (boolean
   (and (string? value) (<= (count value) 64)
        (not (str/ends-with? value "-00:00"))
        (when-let [[_ local zone sign hours minutes]
                   (re-matches #"([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})(?:\.[0-9]+)?(Z|([+-])([01][0-9]|2[0-3]):([0-5][0-9]))" value)]
          (try
            (let [seconds (.toEpochSecond (LocalDateTime/parse local) ZoneOffset/UTC)
                  offset (if (= zone "Z") 0
                             (* (if (= sign "-") -1 1)
                                (+ (* 3600 (Long/parseLong hours)) (* 60 (Long/parseLong minutes)))))
                  year (.getYear (LocalDateTime/ofEpochSecond (- seconds offset) 0 ZoneOffset/UTC))]
              (<= 0 year 9999))
            (catch Exception _ false))))))

(defn uri? [value]
  (boolean
   (and (string? value) (<= (count value) 8192)
        (re-matches #"[\x21-\x7e]+" value)
        (try (.isAbsolute (URI. value)) (catch Exception _ false)))))

(def grandfathered
  #{"en-GB-oed" "i-ami" "i-bnn" "i-default" "i-enochian" "i-hak" "i-klingon"
    "i-lux" "i-mingo" "i-navajo" "i-pwn" "i-tao" "i-tay" "i-tsu"
    "sgn-BE-FR" "sgn-BE-NL" "sgn-CH-DE" "art-lojban" "cel-gaulish"
    "no-bok" "no-nyn" "zh-guoyu" "zh-hakka" "zh-min" "zh-min-nan" "zh-xiang"})

(defn language? [value]
  (boolean
   (and (string? value)
        (or (grandfathered value)
            (and (re-matches #"(?:[a-z]{2,3}|[xX])(?:-[A-Za-z0-9]{1,8})*" value)
                 (try
                   (.setLanguageTag (Locale$Builder.) value)
                   ;; Locale.Builder accepts repeated variants/singletons. BCP 47 does not.
                   (let [parts (str/split (str/lower-case value) #"-")
                         regular (take-while #(not= "x" %) parts)
                         variants (filter #(re-matches #"(?:[a-z0-9]{5,8}|[0-9][a-z0-9]{3})" %)
                                          (take-while #(> (count %) 1) (rest regular)))
                         singletons (filter #(= 1 (count %)) regular)]
                     (and (= (count variants) (count (set variants)))
                          (= (count singletons) (count (set singletons)))))
                   (catch Exception _ false)))))))

(defn cid? [value]
  ;; The data-model specification limits AT Protocol CIDs to the blessed
  ;; CIDv1 / SHA-256 / DAG-CBOR or raw / lowercase-base32 representation.
  (try (codec/cid-bytes value) true (catch Exception _ false)))

(def validators
  {"at-identifier" syntax/at-identifier? "at-uri" syntax/at-uri?
   "did" syntax/did? "handle" syntax/handle? "nsid" syntax/nsid?
   "tid" syntax/tid? "record-key" syntax/record-key? "cid" cid?
   "datetime" datetime? "uri" uri? "language" language?})
