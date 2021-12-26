(ns godotclj.defs
  (:require [godotclj.clang :as clang]))

(defn fn->name
  [fn-name]
  (if (vector? fn-name)
    (first fn-name)
    fn-name))

(def godot-function-bindings
  {:cache     "cache/godot_function_bindings.json"
   :records   "godot_bindings.json"
   :output    {:header         "src/c/wrapper.h"
               :implementation "src/c/wrapper.c"}
   :functions (delay
                (let [godot-bindings-json (godotclj.clang/read-records-json "godot_bindings.json")]
                  (concat (->> (godotclj.clang/gdnative-api-methods godot-bindings-json)
                               (remove #{"godot_get_class_constructor"
                                         "godot_get_global_constants"})
                               vec)
                          (godotclj.clang/gdnative-nativescript-methods godot-bindings-json)
                          [["godot_get_class_constructor" {:return {:wrapped?   true
                                                                    :wrapper    {:name "godot_class_constructor_wrapper"}
                                                                    :arg-type   "godot_class_constructor_wrapper*"
                                                                    :arg-name   "result"
                                                                    :arg-member "result->value"}}]])))})

(def function-bindings
  [{:cache     "cache/wrapper_cache.json"
    :records   "wrapper.json"
    :functions (delay (mapv #(str % "_wrapper") (map fn->name @(:functions godot-function-bindings))))}
   {:cache     "cache/callback_cache.json"
    :records   "callback.json"
    :functions (delay ["get_godot_instance_create_func"
                       "get_godot_instance_destroy_func"
                       "get_godot_property_set_func"
                       "get_godot_property_get_func"
                       "get_godot_instance_method"])}])

(def godot-structs
  {:cache   "cache/godot-structs.json"
   :records {:txt  "godot_bindings.txt"
             :json "godot_bindings.json"}
   :names   (delay (remove #{"godot_class_constructor_wrapper"}
                           (clang/emit-struct-names function-bindings)))})

(def wrapper-structs
  {:cache   "cache/wrapper-structs.json"
   :records {:txt  "wrapper.txt"
             :json "wrapper.json"}
   :names   (delay ["godot_class_constructor_wrapper"])})

(def callback-structs
  {:cache   "cache/callback-structs.json"
   :records {:txt  "callback.txt"
             :json "callback.json"}
   :names   (delay ["instance_method_callback_args"
                   "property_setter_func_args"])})

(def enums
  {:cache   "cache/enums.json"
   :records {:json "godot_bindings.json"}
   :types   ["godot_variant_type"]})
