(ns godotclj.main-jvm
  (:require [godotclj.bindings.godot :as godot]
            [godotclj.clang]
            [godotclj.core]
            [godotclj.defs :as defs]
            [godotclj.proto :as proto]
            [tech.v3.datatype.ffi :as dtype-ffi]))

;; The following is automatically configured by dtype-next,
;; based on "java --add-modules=jdk.incubator.foreign"
;; (cond (dtype-ffi/jdk-mmodel?)
;;       (require '[tech.v3.datatype.ffi.mmodel])
;;       (dtype-ffi/jna-ffi?)
;;       (require '[tech.v3.datatype.ffi.jna]))

;; force jna, so that i know how function pointers work
(require 'godotclj.jna-model)

(def libgodotclj-def
  (dtype-ffi/define-library
    (godotclj.clang/emit defs/function-bindings)
    nil
    {:libraries ["godotclj_gdnative"]}))

(def libgodotclj-inst
  (dtype-ffi/instantiate-library libgodotclj-def "godotclj_gdnative"))

(defn godot_nativescript_init_clojure
  [p-h]
  (godot/set-library-instance! libgodotclj-inst)

  (let [main (godotclj.core/get-main :jvm)]
    (main (proto/->ptr p-h))))
