(ns pds.proxy-api-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [pds.accounts :as accounts]
            [pds.app :as app]
            [pds.crypto :as crypto]
            [pds.db :as db]
            [pds.db-test :as fixture]
            [pds.http :as http]
            [pds.net :as net]
            [pds.net-test :as net-test]
            [pds.proxy-test :as upstream]
            [pds.rate-limit :as rate-limit]
            [pds.redis :as redis]
            [pds.repo-import-test :as imports]
            [pds.server-api-test :as api]
            [pds.service-auth-test :as jwt])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))

(use-fixtures :each fixture/isolated-database)

(defn call [client port method path token headers body]
  (let [builder (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port path))) (.timeout (Duration/ofSeconds 10)))
        _ (when token (.header builder "Authorization" (str "Bearer " token)))
        _ (doseq [[key value] headers] (.header builder key value))
        response (.send ^HttpClient client (-> builder (.method method (if body (HttpRequest$BodyPublishers/ofByteArray body) (HttpRequest$BodyPublishers/noBody))) .build)
                        (HttpResponse$BodyHandlers/ofByteArray))]
    {:status (.statusCode response) :raw (.body response)
     :headers (into {} (map (fn [[key value]] [key (str/join "," value)])) (.map (.headers response)))
     :body (try (json/read-str (String. ^bytes (.body response) "UTF-8")) (catch Exception _ nil))}))

(defn config [{:keys [client fetch]}]
  (assoc (api/settings) :http-client client :fetch fetch :proxy-appview-service upstream/audience
         :proxy-max-request-bytes 64 :proxy-max-response-bytes 128))

(deftest authenticated-forwarding-replaces-credentials-and-preserves-payloads
  (upstream/with-service
    (fn [{:keys [calls doc] :as remote}]
      (let [settings (assoc (config remote) :proxy-labeler-service (str upstream/service-did "#labeler"))
            account (imports/local! settings) did (:did account) access (:accessJwt account)
            server (http/start! settings (app/handler settings fixture/*ds*)) port (:port server)
            method "com.example.read" path (str "/xrpc/" method "?item=a%2Fb&item=c%20d")]
        (swap! doc update "service" #(conj % (assoc (first %) "id" "#labeler")))
        (try
          (with-open [client (HttpClient/newHttpClient)]
            (let [result (call client port "GET" path access
                               {"atproto-proxy" upstream/audience "Cookie" "local=secret" "X-Forwarded-For" "untrusted"
                                "atproto-accept-labelers" "did:web:labels.example.com" "Accept-Language" "fr"
                                "X-Atproto-Custom" "forward-me"} nil)
                  request (last @calls) token (subs (get-in request [:headers "authorization"]) 7)
                  {:keys [claims signature message]} (jwt/decode token)
                  key (with-open [conn (db/connection fixture/*ds*)]
                        (:public_key (first (db/query conn "SELECT public_key FROM repositories WHERE did = ?" did))))]
              (is (= 200 (:status result)))
              (is (= {"ok" true} (:body result)))
              (is (= "item=a%2Fb&item=c%20d" (:query request)))
              (is (= "GET" (:method request)))
              (is (not= access token))
              (is (= did (get claims "iss")))
              (is (= upstream/audience (get claims "aud")))
              (is (= method (get claims "lxm")))
              (is (= 60 (- (get claims "exp") (get claims "iat"))))
              (is (crypto/verify "ES256" key message signature))
              (jwt/upstream! [{:token token :issuer did :audience upstream/audience :method method
                               :didKey (str "did:key:" (crypto/multikey "ES256" key))}])
              (doseq [header ["cookie" "x-forwarded-for" "atproto-proxy"]]
                (is (nil? (get-in request [:headers header]))))
              (is (= "forward-me" (get-in request [:headers "x-atproto-custom"])))
              (is (= "fr" (get-in request [:headers "accept-language"])))
              (is (= "identity" (get-in request [:headers "accept-encoding"])))
              (doseq [header ["set-cookie" "location"]] (is (nil? (get-in result [:headers header]))))
              (is (= "no-store" (get-in result [:headers "cache-control"])))
              (is (= "2222222222222" (get-in result [:headers "atproto-repo-rev"]))))
            (let [head (call client port "HEAD" path access {} nil)]
              (is (= 200 (:status head)))
              (is (= 0 (alength ^bytes (:raw head))))
              (is (= "11" (get-in head [:headers "content-length"])))
              (is (= "HEAD" (:method (last @calls)))))
            (let [bytes (byte-array [0 1 -1 -128 42])
                  posted (call client port "POST" "/xrpc/com.example.write" access {"Content-Type" "application/octet-stream"} bytes)]
              (is (= 200 (:status posted)))
              (is (= (vec bytes) (:body (last @calls))))
              (is (= "application/octet-stream" (get-in (last @calls) [:headers "content-type"]))))
            (is (= 200 (:status (call client port "POST" "/xrpc/com.atproto.moderation.createReport" access {} (byte-array 0)))))
            (is (= (str upstream/service-did "#labeler") (get-in (jwt/decode (subs (get-in (last @calls) [:headers "authorization"]) 7)) [:claims "aud"])))
            (is (= (count @calls) (count (set (map #(get-in (jwt/decode (subs (get-in % [:headers "authorization"]) 7)) [:claims "jti"]) @calls)))))
            (let [before (count @calls)]
              (is (= 200 (:status (call client port "GET" "/xrpc/com.atproto.server.getSession" access {"atproto-proxy" "invalid"} nil))))
              (is (= before (count @calls)))))
          (finally ((:stop! server))))))))

(deftest authorization-and-upstream-failures-do-not-bypass-boundaries
  (upstream/with-service
    (fn [{:keys [mode calls] :as remote}]
      (let [settings (config remote) account (imports/local! settings) access (:accessJwt account)
            server (http/start! settings (app/handler settings fixture/*ds*)) port (:port server)]
        (try
          (with-open [client (HttpClient/newHttpClient)]
            (doseq [token [nil (:refreshJwt account) "invalid"]]
              (is (= 401 (:status (call client port "GET" "/xrpc/com.example.read" token {} nil)))))
            (is (= 400 (:status (call client port "POST" "/xrpc/com.atproto.identity.UpdateHandle" access {} nil))))
            (is (= 405 (:status (call client port "PUT" "/xrpc/com.example.write" access {} nil))))
            (is (= 400 (:status (call client port "GET" "/xrpc/com.example.read" access {"atproto-proxy" upstream/service-did} nil))))
            (is (= 413 (:status (call client port "POST" "/xrpc/com.example.write" access {} (byte-array 65)))))
            (is (= 400 (:status (call client port "POST" "/xrpc/com.example.write" access {"Content-Encoding" "gzip"} (byte-array [1])))))
            (is (empty? @calls))
            (let [password (get-in (api/xrpc client port "POST" "com.atproto.server.createAppPassword" {"name" "unprivileged"} access) [:body "password"])
                  login (:body (api/xrpc client port "POST" "com.atproto.server.createSession" {"identifier" (:did account) "password" password} nil))]
              (is (= 400 (:status (call client port "POST" "/xrpc/chat.bsky.convo.sendMessage" (get login "accessJwt") {} nil))))
              (is (empty? @calls))
              (is (= 200 (:status (call client port "GET" "/xrpc/com.example.read" (get login "accessJwt") {} nil)))))
            (let [password (get-in (api/xrpc client port "POST" "com.atproto.server.createAppPassword"
                                            {"name" "privileged" "privileged" true} access) [:body "password"])
                  login (:body (api/xrpc client port "POST" "com.atproto.server.createSession"
                                        {"identifier" (:did account) "password" password} nil))]
              (is (= 200 (:status (call client port "POST" "/xrpc/chat.bsky.convo.sendMessage" (get login "accessJwt") {} nil)))))
            (doseq [[behavior status error] [[:redirect 502 "UpstreamFailure"] [:error 429 "RateLimitExceeded"]
                                            [:html-error 500 "UpstreamFailure"] [:large 502 "UpstreamFailure"]
                                            [:encoded 502 "UpstreamFailure"] [:drop 502 "UpstreamFailure"]]]
              (reset! mode behavior)
              (let [before (count @calls) response (call client port "POST" "/xrpc/com.example.write" access {} nil)]
                (is (= status (:status response)) (name behavior))
                (is (= error (get-in response [:body "error"])) (name behavior))
                (is (= (inc before) (count @calls)) "No redirects or mutation retries")
                (is (not (str/includes? (String. ^bytes (:raw response) "UTF-8") "private upstream")))))
            (let [before (count @calls)]
              (doseq [status ["deactivated" "taken_down"]]
                (db/transact! fixture/*ds* #(db/execute! % "UPDATE accounts SET status = ?" status))
                (is (= 401 (:status (call client port "GET" "/xrpc/com.example.read" access {} nil)))))
              (is (= before (count @calls)))))
          (finally ((:stop! server))))))))

(deftest reauthentication-after-resolution-and-bounded-concurrency
  (upstream/with-service
    (fn [{:keys [calls on-resolve on-request] :as remote}]
      (let [settings (assoc (config remote) :proxy-max-concurrent 1) account (imports/local! settings)
            ds fixture/*ds* access (:accessJwt account)
            server (http/start! settings (app/handler settings ds)) port (:port server)]
        (try
          (with-open [client (HttpClient/newHttpClient)]
            (let [entered (promise) release (promise)]
              (reset! on-request #(do (deliver entered true) (deref release 5000 nil)))
              (let [pending (future (call client port "GET" "/xrpc/com.example.read" access {} nil))]
                (try
                  (is (= true (deref entered 5000 :timeout)))
                  (is (= 503 (:status (call client port "GET" "/xrpc/com.example.read" access {} nil))))
                  (deliver release true)
                  (is (= 200 (:status (deref pending 5000 {}))))
                  (finally (deliver release true) (reset! on-request nil)))))
            (let [before (count @calls)]
              (reset! on-resolve #(db/transact! ds (fn [conn] (db/execute! conn "UPDATE sessions SET revoked = true"))))
              (is (= 401 (:status (call client port "POST" "/xrpc/com.example.write" access {} (byte-array [1])))))
              (is (= before (count @calls)) "Revocation during DID lookup prevents the upstream mutation")))
          (finally ((:stop! server))))))))

(deftest destination-network-policy-is-enforced-before-sending-credentials
  (upstream/with-service
    (fn [{:keys [doc calls origin] :as remote}]
      (let [settings (config remote) account (imports/local! settings)]
        ;; DID resolution uses the isolated trusted fixture. The actual service
        ;; exchange uses the production address policy, with deterministic DNS.
        (with-open [guarded (net/open-client {:resolver (net-test/resolver "127.0.0.1")})
                    client (HttpClient/newHttpClient)]
          (let [server (http/start! settings (app/handler (assoc settings :http-client guarded) fixture/*ds*))]
            (try
              (is (= 502 (:status (call client (:port server) "POST" "/xrpc/com.example.write" (:accessJwt account) {} (byte-array [42])))))
              (is (empty? @calls) "Private addresses must never receive the service JWT")
              (finally ((:stop! server))))))
        ;; Even the fixture client, which permits loopback, verifies the original
        ;; service hostname against its trusted certificate before sending HTTP.
        (swap! doc assoc-in ["service" 0 "serviceEndpoint"] (str/replace origin "good.example.com" "wrong.example.com"))
        (let [server (http/start! settings (app/handler settings fixture/*ds*))]
          (try
            (with-open [client (HttpClient/newHttpClient)]
              (is (= 502 (:status (call client (:port server) "POST" "/xrpc/com.example.write" (:accessJwt account) {} (byte-array [42])))))
              (is (empty? @calls) "A certificate for another hostname cannot receive credentials"))
            (finally ((:stop! server)))))))))

(deftest timeouts-release-capacity-and-proxy-requests-share-the-rate-limit
  (upstream/with-service
    (fn [{:keys [calls on-request] :as remote}]
      (let [settings (assoc (config remote) :proxy-max-concurrent 1 :proxy-timeout-ms 1000
                            :rate-limiter (rate-limit/memory-limiter {:max-requests 3 :window-ms 60000}))
            account (imports/local! settings) access (:accessJwt account)
            server (http/start! settings (app/handler settings fixture/*ds*))
            port (:port server) entered (promise) release (promise)]
        (try
          (with-open [client (HttpClient/newHttpClient)]
            ;; Establish the TLS connection before measuring an upstream timeout.
            (is (= 200 (:status (call client port "GET" "/xrpc/com.example.read" access {} nil))))
            (reset! on-request #(do (deliver entered true) (deref release 5000 nil)))
            (let [start (System/nanoTime)
                  response (call client port "POST" "/xrpc/com.example.write" access {} (byte-array [1]))]
              (is (= true (deref entered 1000 :timeout)))
              (is (= 502 (:status response)))
              (is (< (/ (- (System/nanoTime) start) 1e6) 4000)))
            (reset! on-request nil)
            (deliver release true)
            (is (= 200 (:status (call client port "GET" "/xrpc/com.example.read" access {} nil))) "Timeout must release the sole proxy permit")
            (let [before (count @calls)
                  limited (call client port "GET" "/xrpc/com.example.read" access {"X-Forwarded-For" "1.2.3.4"} nil)]
              (is (= 429 (:status limited)))
              (is (some? (get-in limited [:headers "retry-after"])))
              (is (= before (count @calls)))))
          (finally (deliver release true) ((:stop! server))))))))

(deftest per-account-proxy-budgets-bound-remote-work
  (upstream/with-service
    (fn [{:keys [calls] :as remote}]
      (let [limiter (redis/open-limiter {:backend "memory" :max-requests 1000 :window-ms 60000
                                         :proxy-account {:enabled true :max-requests 2 :window-ms 60000}})
            settings (assoc (config remote) :rate-limiter limiter)
            alice (imports/local! settings)
            bob (accounts/create! fixture/*ds* settings {"handle" "bob.example.com" "email" "bob@example.com" "password" "test-password"})
            handler (app/handler settings fixture/*ds*)
            release! (fn [response]
                       (when (instance? java.io.Closeable (:body response))
                         (.close ^java.io.Closeable (:body response)))
                       response)
            proxy-get (fn [account] (release! (handler {:uri "/xrpc/com.example.read" :request-method :get
                                                        :headers {"authorization" (str "Bearer " (:accessJwt account))}})))]
        (is (= [200 200] (mapv (comp :status proxy-get) [alice alice])))
        (let [before (count @calls)
              body (proxy [java.io.InputStream] [] (read [& _] (throw (AssertionError. "Over-budget bodies must not be read"))))
              response (handler {:uri "/xrpc/com.example.read" :request-method :post
                                 :headers {"authorization" (str "Bearer " (:accessJwt alice)) "content-type" "application/test"}
                                 :body body})]
          (is (= 429 (:status response)))
          (is (= "RateLimitExceeded" (get (json/read-str (:body response)) "error")))
          (is (contains? (:headers response) "Retry-After"))
          (is (= before (count @calls)) "Over-budget requests never reach the upstream"))
        (is (= 200 (:status (proxy-get bob))) "Each account has an independent budget")
        (is (= 200 (:status (release! (handler {:uri "/xrpc/com.atproto.server.getSession" :request-method :get
                                                :headers {"authorization" (str "Bearer " (:accessJwt alice))}}))))
            "Local routes are not charged against the proxy budget")
        (let [failing (reify
                        rate-limit/Limiter
                        (admit! [_ _] {:allowed? true :retry-after 0})
                        rate-limit/AccountLimiter
                        (admit-account! [_ _ _] (throw (ex-info "limiter outage" {}))))
              handler (app/handler (assoc settings :rate-limiter failing) fixture/*ds*)
              before (count @calls)
              response (handler {:uri "/xrpc/com.example.read" :request-method :get
                                 :headers {"authorization" (str "Bearer " (:accessJwt alice))}})]
          (is (= 503 (:status response)))
          (is (= "RateLimitUnavailable" (get (json/read-str (:body response)) "error")))
          (is (= before (count @calls)) "A shared limiter outage fails closed"))))))
