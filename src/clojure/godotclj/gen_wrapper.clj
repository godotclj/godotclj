(ns godotclj.gen-wrapper
  (:require [godotclj.clang :as clang]
            [godotclj.defs :as defs]
            [godotclj.insn]))

(clang/export-wrapper-fns defs/godot-function-bindings)
