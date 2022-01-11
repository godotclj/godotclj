(ns godotclj.hook-test
  (:require
   [clojure.test :refer [deftest is]]
   [godotclj.hook :as hook]))

(deftest intercept-hook-methods-test
  (let [argument {"Foo" {:base "Node2D"}
                  "Bar" {:base "Control"}}
        result (hook/intercept-hook-methods argument)
        method-set #{"_enter_tree"
                     "_exit_tree"
                     "_get_configuration_warning"
                     "_input"
                     "_physics_process"
                     "_process"
                     "_ready"
                     "_unhandled_input"
                     "_unhandled_key_input"}]
    (is (= (set (keys (get-in result ["Foo" :methods])))
           method-set))
    (is (= (set (keys (get-in result ["Bar" :methods])))
           method-set))))
