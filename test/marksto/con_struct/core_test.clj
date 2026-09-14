(ns marksto.con-struct.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [marksto.con-struct.core :as sut]
            [marksto.con-struct.utils :as utils])
  (:import (clojure.lang ExceptionInfo)
           (java.time Duration)
           (java.util.concurrent StructuredTaskScope$FailedException
                                 StructuredTaskScope$Joiner
                                 StructuredTaskScope$Subtask$State
                                 StructuredTaskScope$TimeoutException
                                 ThreadFactory)))

;; NB: The same task sets the README uses, only made deterministic.

(defn- ok [idx] (fn [] idx))

(defn- boom [idx] (fn [] (throw (ex-info "Oh no!" {:idx idx}))))

(defn- forever [] (fn [] (Thread/sleep 60000) :never))

(def ^:private all-successful (mapv ok (range 5)))
(def ^:private any-failed (mapv #(if (= 3 %) (boom %) (ok %)) (range 5)))
(def ^:private all-failed (mapv boom (range 5)))

(defn- outcome
  "Reduces a single `with-scope` result into something comparable —
   the task result itself, its unavailability, or `[:failed idx]`."
  [result]
  (if (instance? Throwable result)
    (or (:type (ex-data result)) [:failed (:idx (ex-data result))])
    result))

(defn- failure-cause
  "Calls `f`, expecting the scope to fail, and returns the cause it wrapped."
  [f]
  (try (f) nil (catch ExceptionInfo ex (ex-cause ex))))

;;; Joiners

(deftest new-joiner-test
  (testing "every built-in key yields a joiner"
    (doseq [key [:all-successful :any-successful :await-all-successful
                 :await-all :all-until]]
      (is (instance? StructuredTaskScope$Joiner (sut/new-joiner key)) key)))
  (testing "aliases dispatch to the very same joiner as their default key"
    (doseq [[default & aliases]
            [[:all-successful :all-successful-or-throw]
             [:any-successful :any-successful-result-or-throw :any-successful-result]
             [:await-all-successful :await-all-successful-or-throw :await-successful]
             [:await-all :await-all-complete]
             [:all-until :all-subtask-results-until :all-results-until :all-subtasks]]
            alias aliases]
      (is (= (class (sut/new-joiner default)) (class (sut/new-joiner alias))) alias)))
  (testing "a ready-made joiner instance passes straight through"
    (let [joiner (StructuredTaskScope$Joiner/awaitAll)]
      (is (identical? joiner (sut/new-joiner joiner)))))
  (testing ":all-until takes a custom predicate"
    (is (instance? StructuredTaskScope$Joiner
                   (sut/new-joiner :all-until (constantly true))))))

;;; Subtasks

(deftest subtask-test
  (testing "every subtask state there is has a keyword of its own"
    (is (= (set (StructuredTaskScope$Subtask$State/values))
           (set (keys sut/state-enum->kwd)))))
  (testing "a joined scope tells a succeeded subtask from a failed one"
    (with-open [scope (sut/scope:open {:joiner :await-all})]
      (let [good (sut/scope:fork scope (ok 1))
            bad (sut/scope:fork scope (boom 2))]
        (sut/scope:join scope)
        (is (= :subtask.state/success (sut/subtask->state good)))
        (is (= 1 (sut/subtask->result good)))
        (is (= 1 (sut/subtask->result-or-ex good)))
        (is (= :subtask.state/failed (sut/subtask->state bad)))
        (is (= {:idx 2} (ex-data (sut/subtask->ex bad))))
        (is (= {:idx 2} (ex-data (sut/subtask->result-or-ex bad)))))))
  (testing "a subtask the scope cancelled before it completed is unavailable"
    (with-open [scope (sut/scope:open {:joiner      :all-until
                                       :joiner-args [(constantly true)]})]
      (let [stuck (sut/scope:fork scope (forever))]
        (sut/scope:fork scope (ok 1))
        (sut/scope:join scope)
        (is (= :subtask.state/unavailable (sut/subtask->state stuck)))
        ;; NB: An unavailable subtask yields an exception, but never throws.
        (is (= {:type :subtask.state/unavailable}
               (ex-data (sut/subtask->result-or-ex stuck)))))))
  (testing "whatever is not a subtask is left alone"
    (is (= :a (sut/subtask->result-or-ex :a)))))

;;; Scopes

(deftest scope-test
  (testing "a scope opened with no opts awaits all of its subtasks"
    (with-open [scope (sut/scope:open)]
      (let [subtask (sut/scope:fork scope (ok 1))]
        (is (false? (sut/scope:cancelled? scope)))
        (is (nil? (sut/scope:join scope)))
        (is (= 1 (sut/subtask->result subtask))))))
  (testing "a scope cancels itself as soon as its joiner is done"
    (with-open [scope (sut/scope:open {:joiner      :all-until
                                       :joiner-args [(constantly true)]})]
      (sut/scope:fork scope (forever))
      (sut/scope:fork scope (ok 1))
      (sut/scope:join scope)
      (is (true? (sut/scope:cancelled? scope))))))

(deftest arities-test
  (testing "the single-arity call takes tasks, not opts"
    (let [*ran (atom #{})]
      (is (nil? (sut/with-scope [#(swap! *ran conj :a) #(swap! *ran conj :b)])))
      (is (= #{:a :b} @*ran)))
    (is (thrown-with-msg? ExceptionInfo #"join failed"
                          (sut/with-scope [(fn [] (throw (ex-info "boom" {})))])))))

;; NB: Only `anySuccessfulOrThrow` requires a subtask to have been forked.
(deftest no-tasks-test
  (is (nil? (sut/with-scope [])))
  (is (= [] (sut/with-scope {:joiner :all-successful} [])))
  (is (= [] (sut/with-scope {:joiner :all-subtasks} [])))
  (is (thrown-with-msg? ExceptionInfo #"join failed"
                        (sut/with-scope {:joiner :any-successful} []))))

;;; Built-in joiner behaviour, as per the README

(deftest all-successful-behaviour-test
  (is (= [0 1 2 3 4] (sut/with-scope {:joiner :all-successful} all-successful)))
  (testing "a single failed subtask sinks the whole scope"
    (is (= {:idx 3} (ex-data (failure-cause
                               #(sut/with-scope {:joiner :all-successful} any-failed)))))
    (is (some? (failure-cause #(sut/with-scope {:joiner :all-successful} all-failed))))))

;; NB: Guards the JDK 26 shift from subtask streams to plain result lists.
(deftest all-successful-joiner-test
  (let [tasks [(fn [] :a) (fn [] :b)]]
    (testing "yields results in fork order, whatever the joiner returns"
      (is (= [:a :b] (sut/with-scope {:joiner :all-successful} tasks)))
      (is (= [:a :b] (sut/with-scope {:joiner :all-subtasks} tasks))))))

(deftest any-successful-behaviour-test
  (is (contains? (set (range 5))
                 (sut/with-scope {:joiner :any-successful} all-successful)))
  (testing "a failed subtask never wins the race"
    (is (not= 3 (sut/with-scope {:joiner :any-successful} any-failed))))
  (testing "only an all-out failure sinks the scope"
    (is (= "Oh no!" (ex-message (failure-cause
                                  #(sut/with-scope {:joiner :any-successful} all-failed)))))))

;; NB: Guards the JDK-conditional `anySuccessful[Result]OrThrow` call.
(deftest any-successful-joiner-test
  (is (instance? StructuredTaskScope$Joiner (sut/new-joiner :any-successful)))
  (is (= :fast (sut/with-scope {:joiner :any-successful}
                 [(fn [] (Thread/sleep 50) :slow)
                  (fn [] :fast)]))))

(deftest await-all-successful-behaviour-test
  (testing "awaits every subtask, yet yields nothing"
    (let [*ran (atom #{})
          tasks (mapv (fn [idx] #(swap! *ran conj idx)) (range 5))]
      (is (nil? (sut/with-scope {:joiner :await-all-successful} tasks)))
      (is (= (set (range 5)) @*ran))))
  (is (= {:idx 3} (ex-data (failure-cause
                             #(sut/with-scope {:joiner :await-all-successful} any-failed))))))

(deftest await-all-behaviour-test
  (testing "failures are awaited, never raised"
    (is (nil? (sut/with-scope {:joiner :await-all} all-successful)))
    (is (nil? (sut/with-scope {:joiner :await-all} any-failed)))
    (is (nil? (sut/with-scope {:joiner :await-all} all-failed)))))

(deftest all-until-behaviour-test
  (testing "by default, every subtask is let run to its completion"
    (is (= [0 1 2 3 4] (sut/with-scope {:joiner :all-until} all-successful)))
    (is (= [0 1 2 [:failed 3] 4]
           (mapv outcome (sut/with-scope {:joiner :all-until} any-failed))))
    (is (= (mapv (fn [idx] [:failed idx]) (range 5))
           (mapv outcome (sut/with-scope {:joiner :all-until} all-failed)))))
  (testing "a custom predicate decides when the scope is done"
    (is (= [0 1 2 3 4] (sut/with-scope {:joiner      :all-until
                                        :joiner-args [(constantly false)]}
                         all-successful)))
    (is (= [:subtask.state/unavailable 1]
           (mapv outcome (sut/with-scope {:joiner      :all-until
                                          :joiner-args [(constantly true)]}
                           [(forever) (ok 1)]))))))

;;; Scope options

(deftest scope-opts-test
  (testing ":factory supplies the threads the subtasks run on"
    (let [factory (reify ThreadFactory
                    (newThread [_ runnable]
                      (.unstarted (.name (Thread/ofVirtual) "con-struct-test")
                                  runnable)))]
      (is (= ["con-struct-test"]
             (sut/with-scope {:joiner :all-successful :factory factory}
               [#(.getName (Thread/currentThread))])))))
  (testing ":name names the scope itself"
    (with-open [scope (sut/scope:open {:joiner :await-all :name "my-scope"})]
      (is (= "my-scope" (str scope)))))
  (testing ":timeout takes a Duration just as well as a number of millis"
    (doseq [timeout [(Duration/ofMillis 50) 50]]
      (is (thrown? StructuredTaskScope$TimeoutException
                   (sut/with-scope {:joiner :await-all-successful :timeout timeout}
                     [(forever)]))
          timeout))))

(deftest timeout-test
  (testing "a timeout escapes unwrapped"
    (is (thrown? StructuredTaskScope$TimeoutException
                 (sut/with-scope {:joiner :all-successful :timeout 50} [(forever)]))))
  ;; NB: On the JDK 25 a timeout always throws, whatever the joiner in use.
  (when (<= 26 utils/jdk-feature-version)
    (testing ":all-until yields what it has got so far on a timeout"
      (is (= [:subtask.state/unavailable 1]
             (mapv outcome (sut/with-scope {:joiner :all-until :timeout 100}
                             [(forever) (ok 1)])))))))

;;; Failures

(deftest failure-wrapping-test
  (let [opts {:joiner :all-successful}
        ex (try (sut/with-scope opts all-failed) (catch ExceptionInfo ex ex))]
    (testing "the wrapper carries the scope opts and the original cause"
      (is (= "Structured task scope join failed" (ex-message ex)))
      (is (= opts (ex-data ex)))
      (is (= "Oh no!" (ex-message (ex-cause ex)))))
    (testing "the JDK exception is kept around as a suppressed one"
      (is (= [StructuredTaskScope$FailedException]
             (mapv class (Throwable/.getSuppressed ex)))))))

;;; Legacy helpers

(deftest legacy-helpers-test
  (testing "`with-shutdown-on-failure` awaits all and yields nothing"
    (is (nil? (sut/with-shutdown-on-failure all-successful)))
    (is (= {:idx 3} (ex-data (failure-cause
                               #(sut/with-shutdown-on-failure any-failed))))))
  (testing "`with-shutdown-on-success` yields the first successful result"
    (is (contains? (set (range 5)) (sut/with-shutdown-on-success all-successful)))
    (is (= "Oh no!" (ex-message (failure-cause
                                  #(sut/with-shutdown-on-success all-failed)))))))
