(ns godotclj.annotation
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [fipp.clojure :refer [pprint]]))

(def types-file nil)

(defonce types
  (atom {}))

(defmacro annotate
  [form]
  (let [st (str form)]
    (if-let [t (get @types st)]
      `(do
         ~(vary-meta form assoc :tag t))
      `(let [form-sym# ~form]
         (swap! types assoc ~st (type form-sym#))
         form-sym#))))

(defn start-serializer
  [types-file]
  (add-watch types :serialize
             (fn [_ _ _ n]
               (when types-file
                 (spit types-file (with-out-str (pprint n)))))))

(defn load-types
  [f]
  (when f
    (alter-var-root #'types-file (constantly f))
    (reset! types (edn/read-string (slurp f)))
    (pprint @types)))

(defmacro deftypes
  [types-file]
  `(do ~@(for [[_ type-name] (load-types (io/resource types-file))]
           `(import ~type-name))))
