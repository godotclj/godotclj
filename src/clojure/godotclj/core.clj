(ns godotclj.core
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.walk :as walk]
            [clojure.edn :as edn]
            [godotclj.bindings.godot :as godot]))

(defonce godotclj
  (let [f (io/resource "godotclj.edn")]
    (if f
      (edn/read-string (slurp f))
      (throw (ex-info "godotclj.edn not found" {})))))

(defn get-main
  [runtime]
  (requiring-resolve (get-in godotclj [:main runtime])))
