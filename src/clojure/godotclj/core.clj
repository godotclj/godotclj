(ns godotclj.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defonce godotclj
  (let [f (io/resource "godotclj.edn")]
    (if f
      (edn/read-string (slurp f))
      (throw (ex-info "godotclj.edn not found" {})))))

(defn get-main
  [runtime]
  (requiring-resolve (get-in godotclj [:main runtime])))
