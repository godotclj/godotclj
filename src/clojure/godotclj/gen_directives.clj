(ns godotclj.gen-directives
  (:require godotclj.insn)
  (:import [org.graalvm.nativeimage.c CContext$Directives]))

;; NOTE: Generates class implementing interface which has default methods, because gen-class does not allow the use default methods
(godotclj.insn/write-class {:name       'godotclj.context$Directives
                          :flags      #{:public}
                          :interfaces [CContext$Directives]})
