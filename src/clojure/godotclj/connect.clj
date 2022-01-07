(ns godotclj.connect
  "Provide a way to connect signals directly with Clojure functions.

  Usage:
  1. Call `register-callbacks` on startup
  2. Use `connect` to connect signals

  Implementation details:
  When `connect` is called, `signal-registry-handler` checks whether the signal
  is not already connected and if it is not, then `::signal-entry` is made and
  is stored in `signal-registry`. Then Godot signal is connected to signal
  node's (`get-signal-node`) method `signal-handler-method-name` and binds
  `::signal-entry` id with it.

  When a signal is fired, `signal-handler-method-name` method receives signal
  arguments and the `::signal-entry` id. The id is used to find the signal
  entry, grab `:f` and apply signal arguments to it."
  (:require
   [godotclj.api :as api]
   [godotclj.bindings.godot :as godot]
   [godotclj.util :as util]
   [malli.core :as m]
   [malli.error :as m.error]))

(def ^:private signal-handler-method-name "signal_handler")

(def ^:private signal-registry-spec
  [:map-of {:registry {::instance-id :int
                       ::signal-name :string}}
   ::instance-id
   [:map-of ::signal-name fn?]])

(def ^:private signal-registry
  (agent {}
         :validator #(or (m/validate signal-registry-spec %)
                         (->> %
                              (m/explain signal-registry-spec)
                              m.error/humanize
                              str
                              Exception.
                              throw))
         :error-handler (fn [_ e] (println e))))

;; HACK That node might disappear
(defn- get-signal-node
  []
  (->> (api/->object "_Engine")
       .getMainLoop
       .getRoot
       .getChildren
       godot/array->seq
       first
       (api/->object "Object")))

(defn- signal-registered?
  "Check `registry` if there is a handler for `signal-name` on `node`."
  [registry node signal-name]
  (contains? (get registry (.getInstanceId node)) signal-name))

(defn- signal-registry-handler
  [registry node signal-name f]
  (let [instance-id (.getInstanceId node)]
    (if (signal-registered? registry node signal-name)
      (do
        (printf "WARNING: Object %s signal %s has already been connected!\n"
                node
                signal-name)
        registry)
      (do
        ;; HACK this is used to avoid `api/instanceGC` error
        (util/with-logged-errors
          (.connect node
                    signal-name
                    (get-signal-node)
                    signal-handler-method-name
                    [instance-id signal-name]))
        (assoc-in registry [(.getInstanceId node) signal-name] f)))))

(defn- signal-handler
  [_ & args]
  (let [args (vec args)
        i (- (count args) 2)
        [instance-id signal-name] (subvec args i (count args))
        ;; Optional signal arguments (does not include signal name)
        signal-args (subvec args 0 i)]
    (apply (get-in @signal-registry [instance-id signal-name])
           instance-id ;; TODO turn into object
           signal-args)))

(defn connect
  [node signal-name f]
  (send signal-registry signal-registry-handler node signal-name f)
  (await signal-registry))

(defn register-callbacks
  [p-handle & classes]
  (doseq [class classes]
    (godot/register-method p-handle
                           class
                           signal-handler-method-name
                           (util/simplify-method signal-handler))))