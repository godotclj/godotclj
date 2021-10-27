(ns godotclj.strings
  (:require [clojure.string :as str]))

(defn interp
  [s m]
  (str/replace s #"[{][{]([^}]+)[}][}]"
               (fn [match]
                 (str (get m (keyword (second match)))))))
