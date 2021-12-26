(ns godotclj.graalvm-model
  (:require [godotclj.proto :as proto]
            [tech.v3.datatype.ffi :as dtype-ffi])
  (:import [org.graalvm.nativeimage CurrentIsolate StackValue]
           [godotclj context$GodotClassConstructorWrapper]
           [godotclj context$PointerBaseHolder]))

(require '[tech.v3.datatype.ffi.graalvm-runtime])

(extend-type Long
  proto/ToPointer
  (->ptr [value]
    (tech.v3.datatype.ffi.Pointer. value)))

(extend-type tech.v3.datatype.ffi.Pointer
  proto/ToAddress
  (->address [ptr]
    (.address ptr))
  proto/ToPointer
  (->ptr [value]
    value))

(extend-type tech.v3.datatype.ffi.Pointer
  proto/ToFunction
  (->function [ptr _]
    (fn ^tech.v3.datatype.ffi.Pointer [& _]
      (let [holder              (context$PointerBaseHolder. (StackValue/get context$GodotClassConstructorWrapper))

            constructor-wrapper (dtype-ffi/ptr->struct :godot-class-constructor-wrapper (tech.v3.datatype.ffi.Pointer. (.rawValue (.-ptr holder))
                                                                                                                 {:src-buffer holder}))]

        (.put constructor-wrapper :value (proto/->address ptr))

        (-> (.getValue ^context$GodotClassConstructorWrapper (.-ptr holder))
            (.invoke (CurrentIsolate/getCurrentThread))
            .rawValue
            tech.v3.datatype.ffi.Pointer.)))))
