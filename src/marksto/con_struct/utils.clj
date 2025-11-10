(ns marksto.con-struct.utils)

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
