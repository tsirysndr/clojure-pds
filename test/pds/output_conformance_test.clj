(ns pds.output-conformance-test
  (:require [clojure.test :refer [deftest is]]
            [pds.output-conformance :as conformance]))

(deftest output-checks-reject-malformed-responses-and-frames
  (let [session {:id "com.atproto.server.getSession" :kind "output" :encoding "application/json"
                 :content-type "application/json" :value {"did" "did:web:alice.example.com" "handle" "alice.example.com"}}
        frame {:id "com.atproto.sync.subscribeRepos" :kind "message" :header {"op" 1 "t" "#account"}
               :value {"seq" 1 "did" "did:web:alice.example.com" "time" "2026-09-27T00:00:00Z" "active" true}}
        empty {:id "com.atproto.server.deleteSession" :kind "output" :empty true}
        binary {:id "com.atproto.sync.getRepo" :kind "output" :encoding "application/vnd.ipld.car"
                :content-type "application/vnd.ipld.car" :binary true}
        error {:id "com.atproto.sync.subscribeRepos" :kind "message" :header {"op" -1}
               :value {"error" "FutureCursor" "message" "Future cursor"}}]
    (doseq [valid [session frame empty binary error]]
      (is (some? (do (conformance/validate-observation! valid) :valid))))
    (doseq [invalid [(update session :value dissoc "did") (assoc session :content-type "text/plain")
                     (assoc-in session [:value "email"] nil)
                     (update frame :value dissoc "seq") (assoc-in frame [:header "t"] "#unrecognized")
                     (assoc-in frame [:value "$type"] "com.atproto.sync.subscribeRepos#account")
                     (assoc empty :empty false) (assoc binary :binary false)
                     (assoc error :value {}) (assoc-in error [:header "t"] "#account")
                     {:id "com.atproto.server.getSession" :kind "malformed-output"}]]
      (is (thrown? Exception (conformance/validate-observation! invalid))))))
