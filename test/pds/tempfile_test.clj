(ns pds.tempfile-test
  (:require [clojure.test :refer [deftest is]]
            [pds.tempfile :as tempfile])
  (:import [java.io IOException]
           [java.nio ByteBuffer]))

(deftest independent-repeatable-readers-do-not-close-the-owner
  (with-open [channel (tempfile/open-channel!)]
    (.write channel (ByteBuffer/wrap (byte-array [0 1 -1 42])))
    (with-open [first (tempfile/input channel) second (tempfile/input channel)]
      (is (= 0 (.read first)))
      (is (= [0 1 -1 42] (vec (.readAllBytes second))))
      (is (= [1 -1 42] (vec (.readAllBytes first))))
      (is (= -1 (.read first)))
      (is (= 0 (.read first (byte-array 0) 0 0)))
      (.close first)
      (is (thrown? IOException (.read first)))
      (is (.isOpen channel)))
    (with-open [retry (tempfile/input channel)]
      (is (= [0 1 -1 42] (vec (.readAllBytes retry)))))))
