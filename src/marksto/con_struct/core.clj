(ns marksto.con-struct.core
  (:require [marksto.con-struct.utils :as utils])
  (:import (java.time Duration)
           (java.util.concurrent StructuredTaskScope
                                 StructuredTaskScope$Configuration
                                 StructuredTaskScope$FailedException
                                 StructuredTaskScope$Joiner
                                 StructuredTaskScope$Subtask
                                 StructuredTaskScope$Subtask$State)
           (java.util.stream BaseStream)))

;; TODO: Docstrings.
;; TODO: Unit tests.
;; TODO: `ScopedValue`s.

;;; Joiners

(def joiner-aliases
  (-> (make-hierarchy)
      (derive :all-successful-or-throw :all-successful)

      (derive :any-successful-result-or-throw :any-successful)
      (derive :any-successful-result :any-successful)

      (derive :await-all-successful-or-throw :await-all-successful)
      (derive :await-successful :await-all-successful)

      (derive :await-all-complete :await-all)

      (derive :all-subtask-results-until :all-until)
      (derive :all-results-until :all-until)
      (derive :all-subtasks :all-until)))

(defmulti new-joiner
  "Creates a new instance of `StructuredTaskScope$Joiner` to use with a scope."
  {:tag StructuredTaskScope$Joiner}
  (fn [joiner & _args]
    joiner)
  :hierarchy #'joiner-aliases)

(defmethod new-joiner :all-successful [_ & _]
  (StructuredTaskScope$Joiner/allSuccessfulOrThrow))

(defmethod new-joiner :any-successful [_ & _]
  (StructuredTaskScope$Joiner/anySuccessfulResultOrThrow))

(defmethod new-joiner :await-all-successful [_ & _]
  (StructuredTaskScope$Joiner/awaitAllSuccessfulOrThrow))

(defmethod new-joiner :await-all [_ & _]
  (StructuredTaskScope$Joiner/awaitAll))

(defmethod new-joiner :all-until
  ([_] (StructuredTaskScope$Joiner/allUntil (constantly false)))
  ([_ is-done?] (StructuredTaskScope$Joiner/allUntil is-done?)))

(defmethod new-joiner :default [joiner & _] joiner)

;;; Subtasks

(def state-enum->kwd
  {StructuredTaskScope$Subtask$State/SUCCESS     :subtask.state/success
   StructuredTaskScope$Subtask$State/FAILED      :subtask.state/failed
   StructuredTaskScope$Subtask$State/UNAVAILABLE :subtask.state/unavailable})

(defn subtask->state [subtask]
  (state-enum->kwd (StructuredTaskScope$Subtask/.state subtask)))

(defn subtask->result [subtask]
  (StructuredTaskScope$Subtask/.get subtask))

(defn subtask->ex ^Throwable [subtask]
  (StructuredTaskScope$Subtask/.exception subtask))

(defn subtask->result-or-ex [subtask]
  (case (subtask->state subtask)
    :subtask.state/success
    (subtask->result subtask)

    :subtask.state/failed
    (subtask->ex subtask)

    :subtask.state/unavailable
    ;; NB: Avoid throwing here, just return a Clojure exception.
    ;;     Useful when a scope was cancelled before all subtasks
    ;;     were forked or completed other than due to a timeout.
    (ex-info "The subtask result or exception is not available"
             {:type :subtask.state/unavailable})))

;;; Scopes

(defn- config-fn
  [{:keys [factory name timeout] :as _config-opts}
   ^StructuredTaskScope$Configuration default-cfg]
  (cond-> default-cfg
          factory (.withThreadFactory factory)
          name (.withName name)
          timeout (.withTimeout
                    (if (int? timeout) (Duration/ofMillis timeout) timeout))))

(defn scope:open
  "Returns a StructuredTaskScope<?,R> AutoCloseable — use it with `with-open`."
  (^StructuredTaskScope
   []
   (StructuredTaskScope/open))
  (^StructuredTaskScope
   [{:keys [joiner joiner-args]
     :or   {joiner :await-all-successful}
     :as   opts}]
   (let [joiner (if joiner-args
                  (apply new-joiner joiner joiner-args)
                  (new-joiner joiner))]
     (if (empty? (dissoc opts :joiner :joiner-args))
       (StructuredTaskScope/open joiner)
       (StructuredTaskScope/open joiner #(config-fn opts %))))))

(defn scope:fork [scope ^Callable task]
  (StructuredTaskScope/.fork scope task))

(defn scope:join [scope]
  (StructuredTaskScope/.join scope))

(defn scope:cancelled? [scope]
  (StructuredTaskScope/.isCancelled scope))

(defn- ensure-tasks [tasks]
  (when (empty? tasks)
    (throw
      (IllegalArgumentException. "At least one task must be forked before join"))))

(defn with-scope
  "Performs `tasks` within a structured task scope opened with the given `opts`.

   Available options:
   - `:joiner`      — (optional) a keyword `new-joiner` uses for dispatch,
                      or a custom `StructuredTaskScope$Joiner` instance;
                      defines the behaviour of this structured task scope;
   - `:joiner-args` — (optional) a sequence of args for `new-joiner` call;
   - `:factory`     — (optional) a `ThreadFactory` instance used by a new
                      scope; by default, uses thread factory that creates
                      unnamed virtual threads;
   - `:name`        — (optional) a string used as a name of a new scope;
                      used for the purposes of monitoring and management;
   - `:timeout`     — (optional) a `Duration` or a number of millis used
                      as a timeout when the join operation is performed.

   By default, when provided no `opts`, shuts down the scope upon failure and
   rethrows an exception with which some of the tasks has failed.

   If a timeout is provided, may throw a `TimeoutException`. Also, propagates
   `InterruptedException`s and any other internal exceptions.

   Returns the result depending on the used joiner behaviour. Generally, it's
   either a vector of task results (in order of the tasks) or a single (first
   successful) task result."
  ([tasks]
   (with-scope tasks nil))
  ([opts tasks]
   (ensure-tasks tasks)
   (with-open [scope (scope:open opts)]
     (run! #(when-not (scope:cancelled? scope) (scope:fork scope %)) tasks)
     (try
       ;; NB: May throw here, during the join, upon a subtask failure,
       ;;     depending on the joiner's `result` method implementation.
       (when-some [result (scope:join scope)]
         (cond
           (instance? BaseStream result)
           (stream-into! [] (map subtask->result-or-ex) result)

           (seqable? result)
           (mapv subtask->result-or-ex result)

           :else result))
       ;; NB: Let the `StructuredTaskScope$TimeoutException` to escape.
       (catch StructuredTaskScope$FailedException failed-ex
         (utils/wrap-and-throw-cause
           failed-ex "Structured task scope join failed" opts))))))



;;; legacy (pre JDK 25)

(defn with-shutdown-on-failure
  "Performs the given `tasks` within a structured task scope, waits for all of
   them to succeed and returns `nil`, or throws if any of them fails."
  [tasks]
  (with-scope {:joiner :await-all-successful} tasks))

(defn with-shutdown-on-success
  "Performs the given `tasks` within a structured task scope, returns the first
   successful result, or throws if all of them fail."
  [tasks]
  (with-scope {:joiner :any-successful} tasks))
