(ns godotclj.runner
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [godotclj.natives :as natives]
            [babashka.process :refer [process check]]))

(defn absolute-class-path
  [cp]
  (str/join ":" (map (fn [path]
                       (.getAbsolutePath (io/file path)))
                     (str/split cp #":"))))

(defn java-home
  []
  (System/getProperty "java.home"))

(defn class-path
  []
  (absolute-class-path (System/getProperty "java.class.path")))

(defn start
  [& args]
  (let [JAVA_HOME       (java-home)
        LD_LIBRARY_PATH (System/getenv "LD_LIBRARY_PATH")]
    (natives/extract-native-libraries)

    (-> (process `["godot" ~@args]
                 {:err :inherit
                  :out :inherit
                  :env (merge (into {} (System/getenv))
                              {"JAVA_HOME" JAVA_HOME
                               "CLASSPATH" (class-path)
                               "LD_LIBRARY_PATH"
                               (format "%s/lib:%s/lib/server:%s:%s"
                                       JAVA_HOME
                                       JAVA_HOME
                                       (.getAbsolutePath (io/file "natives"))
                                       (or LD_LIBRARY_PATH ""))})})
        (check))
    (shutdown-agents)))
