(ns godotclj.natives
  (:require [clojure.java.io :as io])
  (:import [org.scijava.nativelib NativeLoader]))

(defn extract-native-libraries
  []
  (NativeLoader/loadLibrary "wrapper" nil)
  (NativeLoader/loadLibrary "godotclj_gdnative" nil)
  (.mkdirs (io/file "natives"))
  (doseq [f (filter #(.isFile %) (file-seq (.getNativeDir (NativeLoader/getJniExtractor))))]
    (io/copy f (io/file "natives" (.getName f)))))
