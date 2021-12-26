(ns godotclj.jna-model
  (:require [godotclj.proto :as proto]))

(require '[tech.v3.datatype.ffi.jna])

(extend-type Long
  proto/ToPointer
  (->ptr [value]
    (com.sun.jna.Pointer. value))
  proto/ToAddress
  (->address [value]
    value))

(extend-type tech.v3.datatype.ffi.Pointer
  proto/ToAddress
  (->address [ptr]
    (.address ptr))
  proto/ToPointer
  (->ptr [value]
    value))

(extend-type com.sun.jna.Pointer
  proto/ToAddress
  (->address [ptr]
    (com.sun.jna.Pointer/nativeValue ptr))
  proto/ToPointer
  (->ptr [value]
    (tech.v3.datatype.ffi.Pointer. (com.sun.jna.Pointer/nativeValue value)))
  proto/ToFunction
  (->function [ptr ret-type]
    (fn [& args]
      (.invoke (com.sun.jna.Function/getFunction ptr)
               (case ret-type
                 :pointer com.sun.jna.Pointer)
               (to-array args)))))
