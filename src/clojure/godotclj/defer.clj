(ns godotclj.defer
  "Defer calls to godot via Clojure functions."
  (:require
   [godotclj.bindings.godot :as godot]
   [godotclj.registry-utils :as registry-utils]
   [godotclj.util :as util]))

(def ^:private defer-handler-method-name "defer_handler")

;; we need id to be able to pass data from method
(def ^:private defer-registry-spec
  [:map {:registry {::fn-id :int ; ID to distinguish a function
                    ::last-id :int ; This is used to determine next id
                    ::data [:map-of ::fn-id fn?]}}
   ::data
   ::last-id])

(def ^:private defer-registry
  (registry-utils/make-registry {::data {} ::last-id 0} defer-registry-spec))

(defn- add-fn-to-registry
  "Update `registry` by adding `f` to it."
  [registry f]
  (let [id (inc (::last-id registry))]
    (-> registry
        (assoc-in [::data id] f)
        (assoc ::last-id id))))

(defn- defer-proxy
  [_ fn-id]
  (if-let [f (get-in @defer-registry [::data fn-id])]
    (do (f)
        ;; We cannot use dissoc directly because if there is only one function
        ;; it erases `::data` key and breaks the schema. E.g.
        ;; `(dissoc {::data {42 identity}} ::data 42)` is `{}`
        ;; not `{::data {}}` which we need
        (send defer-registry (fn [m] (update m ::data #(dissoc % fn-id))))
        (await defer-registry))
    (util/warn! (format "No function with id %s found it defer registry!"
                        fn-id))))

(defn defer [f]
  (send defer-registry add-fn-to-registry f)
  (await defer-registry)
  (.callDeferred (util/get-helper-node)
                 defer-handler-method-name (::last-id @defer-registry)))

(defn register-callbacks
  [p-handle & classes]
  (doseq [class classes]
    (godot/register-method p-handle
                           class
                           defer-handler-method-name
                           (util/simplify-method defer-proxy))))
