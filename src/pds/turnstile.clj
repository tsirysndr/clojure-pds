(ns pds.turnstile
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pds.errors :as errors]
            [pds.net :as net])
  (:import [java.net URI]
           [java.nio.charset StandardCharsets]))

(def endpoint "https://challenges.cloudflare.com/turnstile/v0/siteverify")

(defn settings [env]
  (let [site (some-> (get env "PDS_TURNSTILE_SITE_KEY") str/trim not-empty)
        secret (some-> (get env "PDS_TURNSTILE_SECRET_KEY") str/trim not-empty)
        hosts (->> (str/split (get env "PDS_TURNSTILE_HOSTNAMES" "") #",")
                   (map str/trim) (remove str/blank?) set)]
    (when (not= (boolean site) (boolean secret))
      (throw (ex-info "Set both PDS_TURNSTILE_SITE_KEY and PDS_TURNSTILE_SECRET_KEY" {})))
    (when (some #(not (re-matches #"[a-z0-9]+(?:[a-z0-9.-]*[a-z0-9])?" %)) hosts)
      (throw (ex-info "PDS_TURNSTILE_HOSTNAMES must contain DNS hostnames" {})))
    {:turnstile-site-key site :turnstile-secret-key secret :turnstile-hostnames hosts}))

(defn description [settings]
  (when-let [site (:turnstile-site-key settings)] {:siteKey site :action "signup"}))

(defn verify! [settings token]
  (when-let [secret (:turnstile-secret-key settings)]
    (when-not (and (string? token) (<= 1 (count token) 2048))
      (errors/raise! 400 "TurnstileFailed" "Complete the verification and try again"))
    (let [result (try
                   (let [response (net/post-json! (:http-client settings) endpoint
                                    (.getBytes (json/write-str {:secret secret :response token}) StandardCharsets/UTF_8)
                                    {:maximum 16384 :timeout-ms 5000})]
                     (when-not (= 200 (:status response)) (throw (ex-info "Verification unavailable" {})))
                     (json/read-str (String. ^bytes (:body response) StandardCharsets/UTF_8)))
                   (catch Exception _
                     (errors/raise! 503 "TurnstileUnavailable" "Signup verification is temporarily unavailable; try again")))
          hosts (or (not-empty (:turnstile-hostnames settings)) #{(.getHost (URI/create (:public-url settings)))})]
      (when-not (and (true? (get result "success")) (= "signup" (get result "action"))
                     (contains? hosts (get result "hostname")))
        (errors/raise! 400 "TurnstileFailed" "Complete the verification and try again")))))
