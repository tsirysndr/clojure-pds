(ns pds.email
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pds.db :as db])
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util UUID]
           [java.util.concurrent Executors TimeUnit]))

(defn address? [x]
  (boolean (and (string? x) (<= (count x) 320)
                (re-matches #"[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+" x))))

(defn settings
  ([] (settings (System/getenv)))
  ([env]
   (let [url (get env "PDS_EMAIL_WORKER_URL")
         token (get env "PDS_EMAIL_WORKER_TOKEN")
         from (get env "PDS_EMAIL_FROM")]
     (when (some some? [url token from])
       (let [uri (try (URI/create (or url "")) (catch Exception _ nil))]
         (when-not (and uri (.getHost uri) (nil? (.getUserInfo uri)) (nil? (.getFragment uri))
                        (or (= "https" (.getScheme uri))
                            (and (= "http" (.getScheme uri))
                                 (#{"localhost" "127.0.0.1" "[::1]"} (.getHost uri)))))
           (throw (ex-info "PDS_EMAIL_WORKER_URL must be HTTPS (HTTP allowed on loopback only)" {})))
         (when-not (and (string? token) (not (str/blank? token))
                        (not (re-find #"\s" token)))
           (throw (ex-info "PDS_EMAIL_WORKER_TOKEN must be a nonblank token" {})))
         (when-not (address? from)
           (throw (ex-info "PDS_EMAIL_FROM must be an email address" {})))
         {:url url :token token :from from})))))

(defn enqueue!
  "Enqueue within the caller's transaction, atomically with account changes."
  [conn {:keys [to subject text]}]
  (when-not (and (address? to) (string? subject) (<= 1 (count subject) 200)
                 (not (re-find #"[\r\n]" subject))
                 (string? text) (<= 1 (count text) 16000))
    (throw (ex-info "Invalid email message" {})))
  (let [id (UUID/randomUUID)]
    (db/execute! conn "INSERT INTO email_outbox(id, payload) VALUES (?, ?::jsonb)"
                 id (json/write-str {:to to :subject subject :text text}))
    id))

(defn worker-sender
  "Owns an HTTP client. Redirects are disabled to prevent forwarding the token."
  [{:keys [url token from]}]
  (let [client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofSeconds 5))
                   (.followRedirects HttpClient$Redirect/NEVER)
                   .build)]
    {:close! #(.close client)
     :send! (fn [{:keys [id payload]}]
              (let [body (assoc payload :from from :idempotencyKey (str id))
                    request (-> (HttpRequest/newBuilder (URI/create url))
                                (.timeout (Duration/ofSeconds 10))
                                (.header "Authorization" (str "Bearer " token))
                                (.header "Content-Type" "application/json")
                                (.header "Idempotency-Key" (str id))
                                (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                                .build)
                    status (.statusCode (.send client request (HttpResponse$BodyHandlers/discarding)))]
                (when-not (<= 200 status 299)
                  (throw (ex-info "Email Worker rejected request"
                                  {:retryable (or (= status 408) (= status 429) (<= 500 status 599))
                                   :status status})))))}))

(defn- claim! [ds]
  (db/transact!
   ds
   (fn [conn]
     (db/execute! conn "UPDATE email_outbox SET status = 'failed', last_error = 'attempts-exhausted',
                         lease_token = NULL, lease_until = NULL
                         WHERE status = 'sending' AND lease_until <= now() AND attempts >= 10")
     (when-let [row (first (db/query conn
                                    "SELECT id, payload::text, attempts FROM email_outbox
                                     WHERE (status = 'pending' AND available_at <= now())
                                        OR (status = 'sending' AND lease_until <= now())
                                     ORDER BY available_at FOR UPDATE SKIP LOCKED LIMIT 1"))]
       (let [lease (UUID/randomUUID)]
         (db/execute! conn "UPDATE email_outbox SET status = 'sending', attempts = attempts + 1,
                             lease_token = ?, lease_until = now() + interval '60 seconds' WHERE id = ?"
                      lease (:id row))
         (assoc row :lease lease :attempts (inc (:attempts row))
                :payload (json/read-str (:payload row) :key-fn keyword)))))))

(defn deliver-one!
  "Deliver one claimed message. At-least-once; Worker deduplicates stable IDs.
  Store only sanitized error codes, never response bodies or credentials."
  [ds send!]
  (when-let [{:keys [id lease attempts] :as message} (claim! ds)]
    (let [outcome (try (send! message) {:success true}
                       (catch Exception e {:success false
                                           :retryable (not= false (:retryable (ex-data e)))
                                           :error (if-let [s (:status (ex-data e))]
                                                    (str "http-" s) "delivery-failed")}))
          retry? (and (:retryable outcome) (< attempts 10))]
      (db/transact!
       ds
       (fn [conn]
         (if (:success outcome)
           (db/execute! conn "UPDATE email_outbox SET status = 'sent', sent_at = now(), payload = '{}'::jsonb,
                               lease_token = NULL, lease_until = NULL, last_error = NULL
                               WHERE id = ? AND lease_token = ?" id lease)
           (db/execute! conn "UPDATE email_outbox SET status = ?, last_error = ?, lease_token = NULL,
                               lease_until = NULL, available_at = now() + (? * interval '1 second')
                               WHERE id = ? AND lease_token = ?"
                        (if retry? "pending" "failed") (:error outcome)
                        (long (min 3600 (* 5 (Math/pow 2 (dec attempts))))) id lease))))
      (if (:success outcome) :sent (if retry? :retry :failed)))))

(defn start! [ds config]
  (if-not config
    (fn [])
    (let [{:keys [send! close!]} (worker-sender config)
          executor (Executors/newSingleThreadScheduledExecutor)]
      (.scheduleWithFixedDelay
       executor
       ^Runnable (fn []
                   (try (dotimes [_ 20] (deliver-one! ds send!))
                        (catch Exception _
                          (binding [*out* *err*] (println "Email dispatcher failed; retrying")))))
       0 1 TimeUnit/SECONDS)
      (fn [] (.shutdownNow executor) (.awaitTermination executor 15 TimeUnit/SECONDS) (close!)))))
