(ns godotclj.hook
  "Provide a way to attach Clojure functions to class virtual methods.

  Usage:
  1. Call `intercept-hook-methods` on a classmap and register it
  2. Use `add-hook` and `remove-hook` to handle-signals.

  Implementation details:
  Methods are intercepted via `hook-proxy` function that is attached by running
  `register-callbacks`. `hook-proxy` checks registry and calls the function if
  it is in the registry. Registry is manipulated via `add-hook` and `remove-hook`
  functions.

  Currently the implementation is slow and leaks InputEvents."
  (:require
   [godotclj.registry-utils :as registry-utils]
   [godotclj.util :as util]))

(def ^:private hook-registry-spec
  [:map-of {:registry {::instance-id :int
                       ::hook-keyword :keyword}}
   ::instance-id
   [:map-of ::hook-keyword fn?]])

(def ^:private hook-registry
  (registry-utils/make-registry {} hook-registry-spec))

(def ^:private hook-types
  "A map of method names that can be hooked to.

  See https://docs.godotengine.org/en/stable/classes/class_node.html"
  {:enter-tree "_enter_tree"
   :exit-tree "_exit_tree"
   :get-configuration-warning "_get_configuration_warning"
   :input "_input"
   :physics-process "_physics_process"
   :process "_process"
   :ready "_ready"
   :unhandled-input "_unhandled_input"
   :unhandled-key_input "_unhandled_key_input"})

(defn- hook-registered? [registry instance-id hook-type]
  (contains? (get registry instance-id) hook-type))

(defn- add-hook-registry-handler
  [registry node hook-type f]
  (if (hook-registered? registry (.getInstanceId node) hook-type)
    (do
      (util/warn! (format "Object %s hook %s has already been connected!\n"
                          node
                          hook-type))
      registry)
    (if (contains? hook-types hook-type)
      (assoc-in registry [(.getInstanceId node) hook-type] f)
      (do (util/warn! (format "No such hook %s!\n" hook-type))
          registry))))

(defn- remove-hook-registry-handler
  [registry node hook-type]
  (if (hook-registered? registry hook-type (.getInstanceId node))
    (dissoc (.getInstanceId node) hook-type)
    (do (util/warn!
         (format "Object %s hook %s is already removed!\n"
                 node
                 hook-type))
        registry)))

(defn- hook-proxy
  "Method that delegates virtual method calls to `hook-registry` functions."
  [instance-id hook-type & method-args]
  (when (hook-registered? @hook-registry instance-id hook-type)
    (-> (get-in @hook-registry [instance-id hook-type])
        (apply (util/instance-id->instance instance-id)
               method-args))))

(defn- hook-type->proxy-fn
  "Wrap `hook-proxy`, so that it knows which function to call."
  [hook-type]
  (fn [this & method-args]
    (apply hook-proxy (.getInstanceId this) hook-type method-args)))

(defn add-hook
  [node hook-type f]
  (send hook-registry add-hook-registry-handler node hook-type f)
  (await hook-registry))

(defn remove-hook
  [node hook-type]
  (send hook-registry remove-hook-registry-handler node hook-type)
  (await hook-registry))

(defn intercept-hook-methods
  "Connect to `hook-types` methods with hook handlers.

  Returns a new hash map with `hook-types` methods overriden with wrapped
  `hook-proxy` functions (using `hook-type->proxy-fn`)."
  [class-map]
  (util/map-vals
   (fn [props]
     (update props :methods
             #(merge %
                     (->> hook-types
                          (map (fn [[k-name s-name]]
                                 [s-name (hook-type->proxy-fn k-name)]))
                          (into {})))))
   class-map))
