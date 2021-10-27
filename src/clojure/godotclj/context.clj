(ns godotclj.context
  (:gen-class
   :extends godotclj.context$Directives)
  (:import [org.graalvm.word WordBase]))

(defn -getHeaderFiles
  [_]
  ["<wrapper.h>"])
