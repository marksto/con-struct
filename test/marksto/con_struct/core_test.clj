(ns marksto.con-struct.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [marksto.con-struct.core :as sut])
  (:import (java.util.concurrent StructuredTaskScope$Joiner)))

;; NB: Guards the JDK-conditional `anySuccessful[Result]OrThrow` call.
(deftest any-successful-joiner-test
  (is (instance? StructuredTaskScope$Joiner (sut/new-joiner :any-successful)))
  (is (= :fast (sut/with-scope {:joiner :any-successful}
                 [(fn [] (Thread/sleep 50) :slow)
                  (fn [] :fast)]))))
