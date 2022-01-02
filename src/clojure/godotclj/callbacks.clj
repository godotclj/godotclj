(ns godotclj.callbacks
  (:require [clojure.core.async :as async]
            [godotclj.api :as api :refer [mapped-instance]]
            [godotclj.bindings.godot :as godot]
            [godotclj.proto :as proto]
            [godotclj.util :as util])
  (:import [clojure.core.async.impl.channels ManyToManyChannel]))

(defonce signal-agent
  (agent {:callbacks          {}
          :deferred-callbacks {}
          :instance-callbacks {}
          :callback-id        0}))

(defn get-root
  []
  (let [{:keys [get-main-loop]} (api/mapped-instance "_Engine")
        {:keys [get-root]}      (get-main-loop)]
    (get-root)))

;; THis is a bad choice
(defn get-main
  []
  (let [{:keys [get-children]} (get-root)]
    ;; TODO check the retrun type
    (mapped-instance "Object" (first (godot/array->seq (get-children))))))

(defn register-signal-callback
  [{:keys [callback-id] :as state} connect instance-id signal-name cb]
  (let [callback-id (inc callback-id)
        connect?    (empty? (get-in state [:instance-callbacks instance-id signal-name]))
        state       (-> state
                        (assoc :callback-id callback-id)
                        (assoc-in [:instance-callbacks instance-id signal-name callback-id] cb))]

    (when connect?
      (connect signal-name (get-main) "_signal_callback" [instance-id signal-name]))

    state))

(defn unregister-signal-callback
  [state disconnect instance-id signal-name callback-id]
  (let [state (update-in state [:instance-callbacks instance-id signal-name] dissoc callback-id)]
    (when (empty? (get-in state [:instance-callbacks instance-id signal-name]))
      (disconnect signal-name (get-main) "_signal_callback"))
    state))

(defn register-deferred-callback
  [{:keys [callback-id] :as state} cb]
  (let [callback-id (inc callback-id)
        state       (-> state
                        (assoc :callback-id callback-id)
                        (assoc-in [:deferred-callbacks callback-id] cb))
        {:keys [call-deferred]} (get-main)]

    (call-deferred "_deferred_callback" callback-id)

    state))

(defn unregister-deferred-callback
  [state callback-id]
  (update state :deferred-callbacks dissoc callback-id))

(defn signal-callback*
  [state instance-id signal-name]
  (reduce (fn [state [callback-id callback]]
            (callback state instance-id signal-name callback-id))
          state
          (get-in state [:instance-callbacks instance-id signal-name])))

(defn deferred-callback*
  [state callback-id]
  (let [callback (get-in state [:deferred-callbacks callback-id])]
    (callback state callback-id)))

(defn signal-callback
  [_ instance-id signal-name]
  (util/with-logged-errors
    (send signal-agent signal-callback* instance-id signal-name)
    (await signal-agent)))

(defn deferred-callback
  [_ callback-id]
  (util/with-logged-errors
    (send signal-agent deferred-callback* callback-id)
    (await signal-agent)))

(defn signal->channel
  [{:keys [connect disconnect get-instance-id]} signal-name]
  {:pre [(seq signal-name)]}
  (let [instance-id (proto/->clj (get-instance-id))
        output      (async/chan)
        cb          (fn [state instance-id signal-name callback-id]
                      (let [state (unregister-signal-callback state disconnect instance-id signal-name callback-id)]
                        (async/close! output)
                        state))]

    (send signal-agent register-signal-callback connect instance-id signal-name cb)

    output))

(defn deferred->channel
  [f]
  (let [output (async/chan)
        cb     (fn [state callback-id]
                 (util/with-logged-errors
                   (let [result (f)]
                     (if (instance? ManyToManyChannel result)
                       (async/pipe result output)
                       (do (when result
                             (async/put! output result))
                           (async/close! output)))))
                 (unregister-deferred-callback state callback-id))]

    (send signal-agent register-deferred-callback cb)

    output))

(def defer deferred->channel)
(def listen signal->channel)

(defn register-callbacks
  [p-handle & classes]
  (doseq [cls classes]
    (let [register (partial godot/register-method p-handle cls)]
      (register "_signal_callback" (util/simplify-method signal-callback))
      (register "_deferred_callback" (util/simplify-method deferred-callback)))))
