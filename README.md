# con-struct

[![Clojars Project](https://img.shields.io/clojars/v/com.github.marksto/con-struct.svg)](https://clojars.org/com.github.marksto/con-struct)

Clojure wrapper for Structured Concurrency (JDK 25+).

> ⚠️ **DISCLAIMER!**</br>
> JDK 25+ is mandatory in order to use this Clojure wrapper lib!</br>
> Please note this feature is still a _preview feature_ in JDK 25.

## Usage

Add `com.github.marksto/con-struct` to your project dependencies.

```clojure
(require '[marksto.con-struct.core :refer :all])

(with-scope
  {:joiner :all-successful}
  (map (fn [item-id]
         ;; NB: Return Callable, don't call just yet.
         #(do-some-remote-call! http-client item-id))
       (range 10)))
```

### Built-in Joiners

```clojure
(require '[marksto.con-struct.core :refer :all])

(defn a-success [idx]
  (Thread/sleep (rand-int 100))
  (println idx)
  idx)

(def all-successful
  (map (fn [idx] #(a-success idx)) (range 5)))

(defn a-failure [idx]
  (Thread/sleep (rand-int 100))
  (println (format "%d!" idx))
  (throw (ex-info "Oh no!" {:idx idx})))

(def any-failed
  (map (fn [idx] #(if (= 3 idx) (a-failure idx) (a-success idx))) (range 5)))

(def all-failed
  (map (fn [idx] #(a-failure idx)) (range 5)))

;; `:all-successful`

(with-scope
  {:joiner :all-successful}
  all-successful)
;2
;1
;4
;0
;3
;=> [0 1 2 3 4]

(with-scope
  {:joiner :all-successful}
  any-failed)
;0
;2
;3!
;=> ExceptionInfo: Structured task scope join failed {:joiner :all-successful}
;   ExceptionInfo: Oh no! {:idx 3}

(with-scope
  {:joiner :all-successful}
  all-failed)
;1!
;=> ExceptionInfo: Structured task scope join failed {:joiner :all-successful}
;   ExceptionInfo: Oh no! {:idx 1}

;; `:any-successful`

(with-scope
  {:joiner :any-successful}
  all-successful)
;4
;=> 4

(with-scope
  {:joiner :any-successful}
  any-failed)
;3!
;1
;=> 1

(with-scope
  {:joiner :any-successful}
  all-failed)
;2!
;0!
;1!
;3!
;4!
;=> ExceptionInfo: Structured task scope join failed {:joiner :all-successful}
;   ExceptionInfo: Oh no! {:idx 2}

;; `:await-all-successful`

(with-scope
  {:joiner :await-all-successful}
  all-successful)
;3
;4
;2
;0
;1
;=> nil

(with-scope
  {:joiner :await-all-successful}
  any-failed)
;2
;3!
;=> ExceptionInfo: Structured task scope join failed {:joiner :all-successful}
;   ExceptionInfo: Oh no! {:idx 3}

(with-scope
  {:joiner :await-all-successful}
  all-failed)
;2!
;=> ExceptionInfo: Structured task scope join failed {:joiner :all-successful}
;   ExceptionInfo: Oh no! {:idx 2}

;; `:await-all`

(with-scope
  {:joiner :await-all}
  all-successful)
;0
;3
;1
;2
;4
;=> nil

(with-scope
  {:joiner :await-all}
  any-failed)
;2
;3!
;4
;0
;1
;=> nil

(with-scope
  {:joiner :await-all}
  all-failed)
;1!
;3!
;4!
;0!
;2!
;=> nil

;; `:all-until`

(with-scope
  {:joiner :all-until}
  all-successful)
;3
;2
;0
;4
;1
;=> [0 1 2 3 4]

(with-scope
  {:joiner :all-until}
  any-failed)
;4
;2
;3!
;0
;1
;=> [0
;    1
;    2
;    #error{:cause "Oh no!" :data {:idx 3} ...}
;    4]

(with-scope
  {:joiner :all-until}
  all-failed)
;3!
;2!
;1!
;0!
;4!
;=> [#error{:cause "Oh no!" :data {:idx 0} ...}
;    #error{:cause "Oh no!" :data {:idx 1} ...}
;    #error{:cause "Oh no!" :data {:idx 2} ...}
;    #error{:cause "Oh no!" :data {:idx 3} ...}
;    #error{:cause "Oh no!" :data {:idx 4} ...}]

(with-scope
  {:joiner      :all-until
   :joiner-args [(constantly true)]}
  all-successful)
;1
;=> [#error{:cause "The subtask result or exception is not available" :data {:type :subtask.state/unavailable} ...}
;    1
;    #error{:cause "The subtask result or exception is not available" :data {:type :subtask.state/unavailable} ...}
;    #error{:cause "The subtask result or exception is not available" :data {:type :subtask.state/unavailable} ...}
;    #error{:cause "The subtask result or exception is not available" :data {:type :subtask.state/unavailable} ...}]

(with-scope
  {:joiner      :all-until
   :joiner-args [(constantly true)]}
  any-failed)
;1
;3!
;=> [#error{:cause "The subtask result or exception is not available" :data {:type :subtask.state/unavailable} ...}
;    1
;    #error{:cause "The subtask result or exception is not available" :data {:type :subtask.state/unavailable} ...}
;    #error{:cause "The subtask result or exception is not available" :data {:type :subtask.state/unavailable} ...}
;    #error{:cause "The subtask result or exception is not available" :data {:type :subtask.state/unavailable} ...}]

(with-scope
  {:joiner      :all-until
   :joiner-args [(constantly true)]}
  all-failed)
;1!
;=> [#error{:cause "The subtask result or exception is not available" :data {:type :subtask.state/unavailable} ...}
;    #error{:cause "Oh no!" :data {:idx 1} ...}
;    #error{:cause "The subtask result or exception is not available" :data {:type :subtask.state/unavailable} ...}
;    #error{:cause "The subtask result or exception is not available" :data {:type :subtask.state/unavailable} ...}
;    #error{:cause "The subtask result or exception is not available" :data {:type :subtask.state/unavailable} ...}]
```

#### Custom Predicate for `:all-until`

```clojure
(require '[marksto.con-struct.core :refer :all])

(defn ->two-subtasks-failed? []
  (let [*failed-cnt (atom 0)]
    (fn [subtask]
      (boolean
        (when (= :subtask.state/failed (subtask->state subtask))
          (<= 2 (swap! *failed-cnt inc)))))))

(with-scope
  {:joiner      :all-until
   :joiner-args [(->two-subtasks-failed?)]}
  all-successful)
;4
;3
;0
;2
;1
;=> [0 1 2 3 4]

(with-scope
  {:joiner      :all-until
   :joiner-args [(->two-subtasks-failed?)]}
  any-failed)
;0
;3!
;1
;2
;4
;=> [0
;    1
;    2
;    #error{:cause "Oh no!" :data {:idx 3} ...}
;    4]

(with-scope
  {:joiner      :all-until
   :joiner-args [(->two-subtasks-failed?)]}
  all-failed)
;2!
;4!
;=> [#error{:cause "The subtask result or exception is not available" :data {:type :subtask.state/unavailable} ...}
;    #error{:cause "The subtask result or exception is not available" :data {:type :subtask.state/unavailable} ...}
;    #error{:cause "Oh no!" :data {:idx 2} ...}
;    #error{:cause "The subtask result or exception is not available" :data {:type :subtask.state/unavailable} ...}
;    #error{:cause "Oh no!" :data {:idx 4} ...}]
```

## Documentation

Please see the docstring of the `with-scope` function.

## License

Licensed under [EPL 1.0](LICENSE) (same as Clojure).
