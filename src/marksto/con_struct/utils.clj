;; Copyright (c) Mark Sto, 2025. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file `LICENSE` at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns marksto.con-struct.utils
  {:author "Mark Sto (@marksto)"})

(defn wrap-and-throw-cause
  "For a `Throwable` instance `ex`, takes its cause and wraps it into
   an `ExceptionInfo` with the given `msg` string and `data` map, and
   then throws it.

   Takes care of propagating any suppressed exceptions on that cause,
   as well as adds the `ex` itself as a suppressed one to the thrown
   exception."
  [^Throwable ex msg data]
  (let [cause (ex-cause ex)
        _ (prn ex)
        _ (prn msg)
        _ (prn data)
        _ (prn cause)
        wrapped (ex-info msg data cause)]
    (Throwable/.addSuppressed wrapped ex)
    (doseq [sex (Throwable/.getSuppressed cause)]
      (Throwable/.addSuppressed wrapped sex))
    (throw wrapped)))
