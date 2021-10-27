(ns godotclj.insn
  (:require [insn.core :as insn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn write-class
  [data]
  (let [{:keys [name bytes]} (insn/visit data)
        parts                (str/split name #"[.]")
        output               (apply io/file *compile-path*
                                    (conj (vec (butlast parts))
                                          (str (last parts) ".class")))]
    (io/make-parents output)
    (io/copy bytes output)))
