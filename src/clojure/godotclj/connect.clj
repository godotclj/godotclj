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
   [clojure.spec.alpha :as s]
   [godotclj.api :as api]
   [godotclj.bindings.godot :as godot]
   [godotclj.util :as util]))

(def ^:private signal-handler-method-name "signal_handler")

(s/def ::node #(not (nil? %))) ;; TODO Find a better predicate
(s/def ::id nat-int?)
(s/def ::f fn?)
(s/def ::signal-name (s/and string? seq))
(s/def ::signal-entry (s/keys :req [::id ::f ::node ::signal-name]))
(s/def ::signal-registry (s/coll-of ::signal-entry :kind vector?))

(def ^:private signal-registry
  (agent []
         :validator #(or (s/valid? ::signal-registry %)
                         (throw (Exception.
                                 (s/explain-str ::signal-registry %))))
         :error-handler (fn [_ e] (println e))))

(def ^:private generate-signal-id
  (let [id-counter (atom 0)]
    (fn [] (swap! id-counter inc))))

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
  (some #(and (= (::signal-name %) signal-name)
              (= (::node %) node))
        registry))

(defn id->signal-entry
  [id]
  (->> @signal-registry (filter #(= (::id %) id)) first))

(defn- signal-registry-handler
  [registry node signal-name f]
  (let [signal-entry {::id (generate-signal-id)
                      ::node node
                      ::signal-name signal-name
                      ::f f}]
    (if (signal-registered? registry node signal-name)
      registry ;; TODO Notify that connection has failed
      (do
        ;; HACK this is used to avoid `api/instanceGC` error
        (util/with-logged-errors
          (.connect node
                    signal-name
                    (get-signal-node)
                    signal-handler-method-name
                    [(::id signal-entry)]))
        (conj registry signal-entry)))))

(defn- signal-handler
  [_ & args]
  (let [args (vec args)
        signal-entry (id->signal-entry (last args)) ;; last item is the ID
        ;; Optional signal arguments (does not include signal name)
        signal-args (subvec args 0 (dec (count args)))]
    (apply (::f signal-entry) (::node signal-entry) signal-args)))

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
