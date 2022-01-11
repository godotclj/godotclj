(ns godotclj.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [godotclj.bindings.godot :as godot]
            [godotclj.connect :as connect]
            [godotclj.hook :as hook]
            [godotclj.scene-processing :as scene-processing]
            [godotclj.util :as util]))

(defonce project-root
  (let [file (io/file ".")]
    (if (util/godot-project? file)
      file
      (throw (ex-info "Invalid project root!"
                      {:actual-file (.getAbsolutePath file)})))))

(defonce config
  (if-let [file (->> project-root
                     .listFiles
                     (filter #(= (.getName %) "godotclj.edn"))
                     first)]
    (->> file slurp edn/read-string)
    (throw (ex-info "godotclj.edn not found" {}))))

(defn get-main
  [runtime]
  (requiring-resolve (get-in config [:main runtime])))

(defn gen-register-fn
  "Return a function that is used to launch godotclj.

  Settings can be overriden by providing `class-override` map."
  ([]
   (gen-register-fn {}))
  ([class-override]
   (let [classes (-> project-root
                     (scene-processing/project-file->class-map)
                     hook/intercept-hook-methods
                     (scene-processing/merge-class-map class-override))]
     (fn register-methods [p-handle]
       (godot/register-classes p-handle classes)
       (apply connect/register-callbacks p-handle (keys classes))))))

(def connect connect/connect)

(def disconnect connect/disconnect)

(def add-hook hook/add-hook)

(def remove-hook hook/remove-hook)
