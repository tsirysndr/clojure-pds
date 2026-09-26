(ns pds.oauth.dpop
  "DPoP verification and rotating, origin-bound server nonces. No network I/O."
  (:require [clojure.string :as str]
            [pds.crypto :as crypto]
            [pds.oauth.jose :as jose]
            [pds.protocol.codec :as codec])
  (:import [java.net URI]
           [java.security MessageDigest]
           [java.time Instant]))

(defn now [] (.getEpochSecond (Instant/now)))
(defn error! [code] (throw (ex-info (if (= code "use_dpop_nonce") "A current DPoP nonce is required" "Invalid DPoP proof")
                                  {:oauth-error code})))
(defn- invalid! [] (error! "invalid_dpop_proof"))

(defn- nonce-at [{:keys [master-key public-url]} start]
  (let [prefix (str "v1." start)]
    (str prefix "." (crypto/b64 (crypto/hmac master-key (codec/utf8 (str "pds/oauth/dpop-nonce\n" public-url "\n" prefix)))))))

(defn nonce
  "All instances sharing the master key and public origin share a nonce. Rotate
  every 120 seconds; accept older nonces only until 300 seconds after creation."
  [settings]
  (nonce-at settings (* 120 (quot (now) 120))))

(defn nonce-expiry [settings value]
  (try
    (when (and (string? value) (<= (count value) 100))
      (when-let [[_ epoch] (re-matches #"v1\.([0-9]{1,12})\.[A-Za-z0-9_-]{43}" value)]
        (let [start (Long/parseLong epoch) timestamp (now)]
          (when (and (zero? (mod start 120)) (<= start timestamp) (< timestamp (+ start 300))
                     (MessageDigest/isEqual (codec/utf8 value) (codec/utf8 (nonce-at settings start))))
            (+ start 300)))))
    (catch Exception _ nil)))

(defn- normalize-percent [path]
  (str/replace path #"%[0-9A-Fa-f]{2}"
    (fn [escape]
      (let [ch (char (Integer/parseInt (subs escape 1) 16))]
        (if (re-matches #"[A-Za-z0-9._~-]" (str ch)) (str ch) (str/upper-case escape))))))

(defn- remove-dot-segments [path]
  ;; RFC 3986 section 5.2.4. URI.normalize also collapses repeated slashes,
  ;; which are not generally equivalent paths and must remain distinct here.
  (loop [input path output ""]
    (cond
      (empty? input) output
      (str/starts-with? input "../") (recur (subs input 3) output)
      (str/starts-with? input "./") (recur (subs input 2) output)
      (str/starts-with? input "/./") (recur (subs input 2) output)
      (= input "/.") (recur "/" output)
      (or (= input "/..") (str/starts-with? input "/../"))
      (recur (if (= input "/..") "/" (subs input 3)) (subs output 0 (max 0 (.lastIndexOf ^String output "/"))))
      (#{"." ".."} input) output
      :else (let [end (.indexOf ^String input "/" (if (str/starts-with? input "/") 1 0))
                  end (if (neg? end) (count input) end)]
              (recur (subs input end) (str output (subs input 0 end)))))))

(defn target-uri
  "Compare DPoP targets without query/fragment, using RFC 3986 scheme/host,
  default-port, percent-encoding and dot-segment normalization. The request URL
  must come from configured public origin + request path, never forwarding headers."
  [value]
  (when-not (and (string? value) (<= 1 (count value) 8192)) (invalid!))
  (let [uri (URI/create value) scheme (some-> (.getScheme uri) str/lower-case)
        host (some-> (.getHost uri) str/lower-case) port (.getPort uri)]
    (when-not (and (#{"http" "https"} scheme) (seq host) (nil? (.getUserInfo uri))
                   (or (= -1 port) (<= 1 port 65535))) (invalid!))
    (let [port (if (or (= -1 port) (= port (if (= scheme "https") 443 80))) "" (str ":" port))
          path (normalize-percent (if (seq (.getRawPath uri)) (.getRawPath uri) "/"))]
      (str scheme "://" host port (remove-dot-segments path)))))

(defn verify!
  "Verify one proof, returning a candidate for durable consumption. :method and
  :url are the trusted HTTP target. Resource requests MUST supply both :access-token
  and its stored :jkt; PAR/token requests may supply a previously bound :jkt.
  This does not itself prevent replay: call proof-store/consume! before acceptance."
  [settings token {:keys [method url access-token jkt]}]
  (try
    (let [{:keys [header claims] :as parsed} (jose/parse! token)
          key (jose/public-key! (get header "jwk")) timestamp (now)
          issued (get claims "iat") id (get claims "jti")
          expires (get claims "exp") not-before (get claims "nbf")]
      (when-not (and (= "dpop+jwt" (get header "typ")) (string? method) (= method (get claims "htm"))
                     (= (target-uri url) (target-uri (get claims "htu")))
                     (string? id) (<= 1 (alength (codec/utf8 id)) 256)
                     (integer? issued) (<= 0 issued Long/MAX_VALUE) (<= (- timestamp 300) issued (+ timestamp 30))
                     (or (not (contains? claims "exp"))
                         (and (integer? expires) (< timestamp expires) (<= expires Long/MAX_VALUE)))
                     (or (not (contains? claims "nbf"))
                         (and (integer? not-before) (<= 0 not-before timestamp)))
                     (or (nil? jkt) (= jkt (:jkt key)))
                     (or (nil? access-token)
                         (and (string? access-token) (seq access-token) (some? jkt)
                              (= (crypto/digest-token access-token) (get claims "ath")))))
        (invalid!))
      (jose/verify! key parsed)
      (let [expiry (or (nonce-expiry settings (get claims "nonce")) (error! "use_dpop_nonce"))]
        {:jkt (:jkt key) :jti-hash (crypto/digest-token id) :claims claims
         :expires-at (min expiry (+ issued 301) (or expires Long/MAX_VALUE))}))
    (catch Exception e
      (if (:oauth-error (ex-data e)) (throw e) (invalid!)))))
