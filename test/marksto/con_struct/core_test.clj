(ns marksto.con-struct.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [marksto.con-struct.core :as sut])
  (:import (clojure.lang ExceptionInfo)
           (java.util.concurrent StructuredTaskScope$Joiner)))

(deftest arities-test
  (testing "the single-arity call takes tasks, not opts"
    (let [*ran (atom #{})]
      (is (nil? (sut/with-scope [#(swap! *ran conj :a) #(swap! *ran conj :b)])))
      (is (= #{:a :b} @*ran)))
    (is (thrown-with-msg? ExceptionInfo #"join failed"
                          (sut/with-scope [(fn [] (throw (ex-info "boom" {})))])))))

;; NB: Guards the JDK-conditional `anySuccessful[Result]OrThrow` call.
(deftest any-successful-joiner-test
  (is (instance? StructuredTaskScope$Joiner (sut/new-joiner :any-successful)))
  (is (= :fast (sut/with-scope {:joiner :any-successful}
                 [(fn [] (Thread/sleep 50) :slow)
                  (fn [] :fast)]))))

;; NB: Guards the JDK 26 shift from subtask streams to plain result lists.
(deftest all-successful-joiner-test
  (let [tasks [(fn [] :a) (fn [] :b)]]
    (testing "yields results in fork order, whatever the joiner returns"
      (is (= [:a :b] (sut/with-scope {:joiner :all-successful} tasks)))
      (is (= [:a :b] (sut/with-scope {:joiner :all-subtasks} tasks))))))

;; NB: Only `anySuccessfulOrThrow` requires a subtask to have been forked.
(deftest no-tasks-test
  (is (nil? (sut/with-scope [])))
  (is (= [] (sut/with-scope {:joiner :all-successful} [])))
  (is (= [] (sut/with-scope {:joiner :all-subtasks} [])))
  (is (thrown-with-msg? ExceptionInfo #"join failed"
                        (sut/with-scope {:joiner :any-successful} []))))
