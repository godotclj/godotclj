(ns godotclj.proto)

(defprotocol ToFunction
  (->function [_ ret-type]))

(defprotocol ToPointer
  (->ptr [_]))

(defprotocol ToAddress
  (->address [_]))

(defprotocol ToVariant
  (->variant [_]))

(defprotocol ToClojure
  (->clj [_]))

(defprotocol PVariantToObject
  (pvariant->object [_]))
