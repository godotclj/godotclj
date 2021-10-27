(ns godotclj.paths
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(print (str/join ":" (map (fn [path]
                             (.getAbsolutePath (io/file path)))
                          (str/split (slurp (io/reader *in*)) #":"))))
