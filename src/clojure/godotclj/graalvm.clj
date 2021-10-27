(ns godotclj.graalvm
  (:require [camel-snake-kebab.core :as csk]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [insn.core :as insn]
            [godotclj.strings :as strings])
  (:import [org.graalvm.nativeimage.c.function CFunctionPointer]
           [org.graalvm.nativeimage.c.function InvokeCFunctionPointer]
           [org.graalvm.nativeimage IsolateThread]
           [org.graalvm.word PointerBase]
           [org.graalvm.nativeimage.c.struct CField]
           [org.graalvm.nativeimage.c.struct CStruct]
           [org.graalvm.nativeimage.c CContext]
           [org.graalvm.nativeimage.c.type VoidPointer]
           [godotclj context]))



(defn function-wrapper-context-name
  [{function-name :name :as wrapper} & {:keys [suffix] :or {suffix ""}}]
  ;; TODO make name greppable
  (symbol (strings/interp "godotclj.context${{function-name}}{{suffix}}"
                          {:function-name (csk/->PascalCase function-name)
                           :suffix        suffix})))

(defn function-wrapper
  [{function-name :name :as wrapper}]
  (let [function-pointer-sym (function-wrapper-context-name wrapper :suffix "FunctionPointer")]
    [{:name       (function-wrapper-context-name wrapper)
      :flags      #{:public :interface}
      :annotations [[CContext godotclj.context]
                    [CStruct function-name]]
      :interfaces [PointerBase]
      :methods    [{:flags       #{:public :abstract}
                    :annotations [[CField "value"]]
                    :name        "getValue"
                    :desc        [function-pointer-sym]}

                   {:flags       #{:public :abstract}
                    :annotations [[CField "value"]]
                    :name        "setValue"
                    :desc        [function-pointer-sym :void]}]}

     {:name       function-pointer-sym
      :flags      #{:public :interface}
      :interfaces [CFunctionPointer]
      :methods    [{:flags       #{:public :abstract}
                    :annotations [[InvokeCFunctionPointer {}]]
                    :name        :invoke
                    :desc        [IsolateThread VoidPointer]}]}]))
