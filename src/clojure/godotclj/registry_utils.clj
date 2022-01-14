(ns godotclj.registry-utils
  "Tools to build registries -- agents with validation."
  (:require
   [malli.core :as m]
   [malli.error :as m.error]))

(defn make-registry
  "Make registry agent with `initial-state` and validate with malli `schema`."
  [initial-state schema]
  (agent initial-state
         :validator #(or (m/validate schema %)
                         (->> %
                              (m/explain schema)
                              m.error/humanize
                              str
                              Exception.
                              throw))
         :error-handler (fn [registry e]
                          (println e)
                          (println (str "current registry: "
                                        @registry)))))
