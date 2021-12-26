(ns godotclj.gen-bindings
  (:require [godotclj.clang]
            [godotclj.defs :as defs]
            [godotclj.graalvm]
            [godotclj.insn]
            [tech.v3.datatype.ffi.graalvm :as graalvm])
  (:import [org.graalvm.word PointerBase]
           [org.graalvm.word PointerBase]))

(run! godotclj.insn/write-class (godotclj.graalvm/function-wrapper {:name "godot_class_constructor_wrapper"}))

(godotclj.insn/write-class {:name   'godotclj.context$PointerBaseHolder
                          :fields [{:flags #{:public}
                                    :name  "ptr"
                                    :type  PointerBase}]
                          :methods [{:name :init, :desc [PointerBase :void]
                                     :emit [[:aload 0]
                                            [:invokespecial :super :init [:void]]
                                            [:aload 0]
                                            [:aload 1]
                                            [:putfield :this "ptr" PointerBase]
                                            [:return]]}]})

(def godotclj-def (graalvm/define-library
                  (godotclj.clang/emit defs/function-bindings)
                  nil
                  {:header-files ["<wrapper.h>" "<callback.h>"]
                   :libraries    ["wrapper"]
                   :classname    'godotclj.bindings.GraalVM}))
