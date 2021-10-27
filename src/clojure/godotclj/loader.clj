(ns godotclj.loader
  (:gen-class
   :methods [^:static [clojure_call [String String long] void]]))

(defn -clojure_call
  [namespace-name function-name p-args]
  (try
    (.setContextClassLoader (Thread/currentThread)
                            (java.lang.ClassLoader/getSystemClassLoader))

    (let [f (requiring-resolve (symbol namespace-name function-name))]
      (f p-args))
    (catch Throwable t
      (println :t t))))
