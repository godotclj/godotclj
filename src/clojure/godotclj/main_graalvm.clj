(ns godotclj.main-graalvm
  (:require [godotclj.bindings.godot :as godot]
            [godotclj.core]
            [tech.v3.datatype.ffi.graalvm :as graalvm])
  (:gen-class))

(require 'godotclj.graalvm-model)

(defn -main
  [& _])

(def main
  (godotclj.core/get-main :graalvm))

(defn godot_nativescript_init_clojure
  [p-handle]
  (godot/set-library-instance! (godotclj.bindings.GraalVM.))
  (when p-handle
    (main p-handle)))

(graalvm/expose-clojure-functions
 {#'godotclj.main-graalvm/godot_nativescript_init_clojure {:rettype :void
                                                         :argtypes [['p-handle :pointer]]}

  #'godotclj.bindings.godot/create_func_callback          {:rettype  :void
                                                         :argtypes [['args :pointer]]}

  #'godotclj.bindings.godot/destroy_func_callback         {:rettype  :void
                                                         :argtypes [['args :pointer]]}

  #'godotclj.bindings.godot/free_func_callback            {:rettype  :void
                                                         :argtypes [['args :pointer]]}

  #'godotclj.bindings.godot/property_set_func_callback    {:rettype :void
                                                         :argtypes [['args :pointer]]}

  #'godotclj.bindings.godot/property_get_func_callback    {:rettype :void
                                                         :argtypes [['args :pointer]]}

  #'godotclj.bindings.godot/instance_method_callback      {:rettype :void
                                                         :argtypes [['args :pointer]]}}

 'godotclj.bindings.GraalVMMain nil)
