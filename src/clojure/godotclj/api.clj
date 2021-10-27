(ns godotclj.api
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [godotclj.bindings.godot :as godot]
            [godotclj.proto :as proto]
            [tech.v3.datatype :as dtype]
            [tech.v3.datatype.ffi :as dtype-ffi]
            [tech.v3.datatype.native-buffer :as native-buffer]
            [tech.v3.datatype.struct :as dtype-struct]
            [camel-snake-kebab.core :as csk])
  (:import tech.v3.datatype.ffi.Pointer))

(defonce api-json
  (delay (walk/keywordize-keys (json/read-str (slurp (or (io/resource "api.json")
                                                         (io/file "godot-headers/api.json")))))))

(defn ob-def
  [ob-name]
  (->> @api-json
       (filter (comp #{ob-name} :name))
       first))

(defn instance
  [ob-name]
  (let [ob-meta (ob-def ob-name)]
    (if (:singleton ob-meta)
      (godot/godot_global_get_singleton_wrapper (dtype-ffi/string->c (:singleton_name ob-meta)))
      (godot/construct ob-name))))

(defn hyphenate
  [s]
  (str/replace s "_" "-"))

(defn method-name->keyword
  [n]
  (keyword (hyphenate n)))

(defn ob-methods
  [ob-name & {:keys [recursive] :or {recursive true}}]
  (let [ob      (ob-def ob-name)
        base    (:base_class ob)
        methods (concat (when (and (seq base) recursive)
                          (ob-methods base))
                        (:methods ob))]
    methods))

(defn method-call
  [method f ob args]
  (let [buf     (-> (dtype/make-container :native-heap :int64 (count args))
                    (dtype/as-native-buffer))
        writer  (dtype/as-writer buf)
        variant (dtype-struct/new-struct :godot-variant {:container-type :native-heap})]

    (doseq [[i child] (map vector (range) args)]
      (.writeLong writer i (proto/->address (dtype-ffi/->pointer (proto/->variant child)))))

    (godot/godot_method_bind_call_wrapper f
                                          (proto/->ptr ob)
                                          (dtype-ffi/->pointer buf)
                                          (count args)
                                          nil
                                          (dtype-ffi/->pointer variant))
    variant))

(defn ob-method**
  [ob-type {:keys [return_type arguments] :as method}]
  (when-let [f (godot/godot_method_bind_get_method_wrapper
                (dtype-ffi/string->c ob-type)
                (dtype-ffi/string->c (:name method)))]
    (fn [ob & args]
      (try
        ;;      (println :ob-method/args (interleave (map :type arguments) args))
        (let [variant (method-call method f ob args)
              object  (case return_type
                        "void"            nil
                        "String"          (some-> variant godot/variant->str)
                        "Array"           (some-> variant godot/variant->array)
                        "PoolStringArray" (some-> variant godot/variant->pool-string-array)
                        "Rect2"           (some-> variant godot/variant->rect2)
                        "Vector2"         (some-> variant godot/variant->vector2)
                        "bool"            (some-> variant godot/variant->bool)
                        "float"           (some-> variant godot/variant->real)
                        "int"             (some-> variant godot/variant->int)
                        (case (godot/get-variant-type variant)
                          :godot-variant-type-int    (some-> variant godot/variant->int)
                          :godot-variant-type-object (some-> variant godot/variant->object)
                          :godot-variant-type-nil    nil))]
          object
          )
        (catch Exception e
          (println e))))))

(declare mapped-instance)

(defn ob-method*
  [ob-type {:keys [return_type] :as method}]
  (let [f (ob-method** ob-type method)]
    (fn [ob & args]
      (let [object (apply f ob args)]
        (when (and object return_type)
          (case return_type
            ("bool" "int" "float") object
            (mapped-instance return_type object)))))))

(defn ob-method
  [ob-type method ob]
  (partial (ob-method* ob-type method) ob))

(def ob-method-memoized*
  (memoize ob-method*))

(defn keyword->method-name
  [k]
  (csk/->snake_case_string k))

(defn get-at
  [m k]
  (or (get m k)
      (when-let [method (get-in m [:godot/methods k])]
        (let [f (ob-method-memoized* (:godot/type m) method)]
          (partial f (:godot/object m))))))

(deftype InstanceGc [m]
  clojure.lang.ILookup
  (valAt [_ k]
    (get-at m k))
  java.lang.Object
  (finalize [this]
    (let [{:keys [unreference]} m]
      (when unreference
        (println :unreference (:godot/type this))
        (unreference))))
  ;; TODO dtype ->pointer
  proto/ToVariant
  (->variant
    [this]
    (case (:godot/type this)
      ;; TODO more types?
      "Vector2" (godot/vector2->variant (:godot/object this))
      (godot/object->variant (:godot/object this))))

  dtype-ffi/PToPointer
  (convertible-to-pointer? [item] true)
  (->pointer [this]
    (dtype-ffi/->pointer (:godot/object this))))

(defn instance-gc
  [m]
  (let [ins                 (->InstanceGc m)
        {:keys [reference]} ins]
    (when reference
      (reference))
    ins))

(defn ob-properties
  [ob-name]
  (let [ob      (ob-def ob-name)
        base    (:base_class ob)
        properties (concat (when (seq base)
                          (ob-properties base))
                        (:properties ob))]
    properties))

(defn method-defs*
  [ob-type]
  (reduce #(assoc %1 (keyword (method-name->keyword (:name %2))) %2) {} (ob-methods ob-type)))

(def method-defs-memoized (memoize method-defs*))

(defn mapped-instance
  ([ob-type]
   (mapped-instance ob-type (instance ob-type)))
  ([ob-type ob]
   (let [method-defs (method-defs-memoized ob-type)
         ob-type     (if-let [get-class-def (:get-class method-defs)]
                       (godot/get-class ob)
                       ob-type)
         method-defs (method-defs-memoized ob-type)]

     (instance-gc {:godot/object  ob
                   :godot/type    ob-type
                   :godot/methods method-defs}))))

(defn pool-string-array->vec
  [coll]
  (vec
   (for [i (range (godot/pool-string-array-size coll))]
     (godot/pool-string-array-get coll i))))

(defrecord Vec2 [xs]
  proto/ToVariant
  (->variant [v]
    (godot/vector2->variant (godot/new-vector2 xs))))

(defn vec2
  [xs]
  (->Vec2 (mapv float xs)))

(defn vec2-xs
  [v]
  (:xs v))

(defn vec2?
  [v]
  (instance? Vec2 v))
