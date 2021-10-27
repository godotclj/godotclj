(ns godotclj.api.basic
  (:require [godotclj.api :refer [mapped-instance ob-def api-json method-name->keyword]]
            [camel-snake-kebab.core :as csk]))

;; (defmacro defapi
;;   []
;;   `(do
;;      ~@(for [{ob-type :name methods :methods} api-json
;;              method-name                      (map :name methods)]
;;          `(defn ~(csk/->kebab-case-symbol (str ob-type "-" method-name))
;;             [ob# & args#]
;;             (apply (get ob# ~(method-name->keyword method-name)) args#)))))

;;(defapi)

(comment
;;  (:godot/object (basic/node-get-children (get-main)))
  )
