(ns godotclj.gen-bindings
  (:require [tech.v3.datatype.ffi.graalvm :as graalvm]
            [tech.v3.datatype.ffi.clang :as ffi-clang]
            [godotclj.defs :as defs]
            [insn.core :as insn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [camel-snake-kebab.core :as csk]
            [godotclj.strings :as strings]
            [godotclj.clang]
            [godotclj.graalvm]
            [godotclj.insn])
  (:import [org.graalvm.nativeimage.c.function CFunctionPointer]
           [org.graalvm.nativeimage.c.function InvokeCFunctionPointer]
           [org.graalvm.nativeimage IsolateThread]
           [org.graalvm.word PointerBase]
           [org.graalvm.nativeimage.c.struct CField]
           [org.graalvm.nativeimage.c.struct CStruct]
           [org.graalvm.nativeimage.c CContext]
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
