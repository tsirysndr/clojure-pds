(ns pds.errors)
(defn raise! [status error message]
  (throw (ex-info message {:xrpc true :status status :error error})))
(defn invalid! [message] (raise! 400 "InvalidRequest" message))
