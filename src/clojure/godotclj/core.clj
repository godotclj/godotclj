(ns godotclj.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [godotclj.bindings.godot :as godot]
            [godotclj.connect :as connect]
            [godotclj.defer :as defer]
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
       (apply connect/register-callbacks p-handle (keys classes))
       (apply defer/register-callbacks p-handle (keys classes))))))

(defn connect
  "Connect `node` signal `signal-name` with function `f`."
  [node signal-name f]
  (connect/connect node signal-name f))

(defn disconnect
  "Disconnect `node` signal `signal-name`."
  [node signal-name]
  (connect/disconnect node signal-name))

(defn add-hook
  "Connect to `node`'s `hook-type` with `f`.

  `hook-type` is a keyword that represents a Godot virtual method (e.g.
  `:ready` or `:physics-process`)."
  [node hook-type f]
  (hook/add-hook node hook-type f))

(defn remove-hook
  "Remove `node`'s hook with type `hook-type`."
  [node hook-type]
  (hook/remove-hook node hook-type))

(defn defer
  "Call `f` when Godot is not busy (same as Godot's `.callDeferred`)."
  [f]
  (defer/defer f))
