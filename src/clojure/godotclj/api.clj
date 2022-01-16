(ns godotclj.api
  (:require [camel-snake-kebab.core :as csk]
            [godotclj.ffi.gdnative :as gdnative]
            [godotclj.api.gdscript :as gdscript]
            [godotclj.api.gen-gdscript :as gen-gdscript
             :refer [ob-def method-name->keyword ob-methods]]
            [godotclj.bindings.godot :as godot]
            [godotclj.proto :as proto]
            [tech.v3.datatype :as dtype]
            [tech.v3.datatype.ffi :as dtype-ffi]
            [tech.v3.datatype.struct :as dtype-struct])
  (:import [godotclj.bindings.godot Variant]))

(declare mapped-instance)

(defn instance
  [ob-name]
  (let [ob-meta (ob-def ob-name)]
    (if (:singleton ob-meta)
      (gdnative/godot_global_get_singleton_wrapper (dtype-ffi/string->c (:singleton_name ob-meta)))
      (godot/construct ob-name))))

(defn method-call
  [_ f ob args]
  (let [buf     (-> (dtype/make-container :native-heap :int64 (count args))
                    (dtype/as-native-buffer))
        writer  (dtype/as-writer buf)
        variant (dtype-struct/new-struct :godot-variant {:container-type :native-heap})]

    (doseq [[i child] (map vector (range) args)]
      (.writeLong writer i (.address (dtype-ffi/->pointer (proto/->variant child)))))

    (gdnative/godot_method_bind_call_wrapper f
                                             ob
                                             (dtype-ffi/->pointer buf)
                                             (count args)
                                             nil
                                             (dtype-ffi/->pointer variant))
    variant))

(defn ->gdscript-instance
  [ob-type m]
  (case ob-type
    ("String" "Rect2") (:godot/object m)
    "Vector2"          m
    "Vector3"          m
    "PoolStringArray"  m
    "NodePath"         m
    "Array"            m
    "enum.Error"       (:godot/object m)
    (gdscript/->instance ob-type m)))

(defn ob-method**
  [ob-type {_ :name :keys [return_type _] :as method}]
  (when-let [f (gdnative/godot_method_bind_get_method_wrapper
                (dtype-ffi/string->c ob-type)
                (dtype-ffi/string->c (:name method)))]
    (fn [ob & args]
      (try
        (let [variant (method-call method f ob args)
              object  (case return_type
                        "void"            nil
                        "String"          (some-> variant godot/variant->str)
                        "Array"           (some-> variant godot/variant->array)
                        "PoolStringArray" (some-> variant godot/variant->pool-string-array)
                        "Rect2"           (some-> variant godot/variant->rect2 godot/rect2->indexed)
                        "Vector2"         (some-> variant godot/variant->vector2)
                        "Vector3"         (some-> variant godot/variant->vector3)
                        "bool"            (some-> variant godot/variant->bool)
                        "float"           (some-> variant godot/variant->real)
                        "int"             (some-> variant godot/variant->int)
                        (case (godot/get-variant-type variant)
                          :godot-variant-type-int       (some-> variant godot/variant->int)
                          :godot-variant-type-object    (some-> variant godot/variant->object)
                          :godot-variant-type-node-path (some-> variant godot/variant->node-path)
                          :godot-variant-type-nil       nil))]
          object
          )
        (catch Exception e
          (println e))))))

(defn ob-method*
  [ob-type {:keys [return_type] :as method}]
  (let [f (ob-method** ob-type method)]
    (fn [wrapper ob & args]
      (let [object (apply f ob args)]
        (assert return_type)
        (case return_type
          ("bool" "int" "float") object
          "Vector2"              (godot/->Vector2 object)
          "Vector3"              (godot/->Vector3 object)
          "PoolStringArray"      (godot/pool-string-array->indexed object)
          "NodePath"             (godot/->NodePath object)
          "Array"                (godot/->IndexedArray (godot/array->seq object) object)
          (when object
            (mapped-instance return_type object wrapper)))))))

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
          (partial f (:godot/wrapper m) (:godot/object m))))))

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
      "Vector3" (godot/vector3->variant (:godot/object this))
      (godot/object->variant (:godot/object this))))

  dtype-ffi/PToPointer
  (convertible-to-pointer? [_] true)
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
   (mapped-instance ob-type ob #'->gdscript-instance))
  ([ob-type ob wrapper]
   {:pre [ob]}
   (let [method-defs (method-defs-memoized ob-type)
         ob-type     (if-let [_ (:get-class method-defs)]
                       (godot/get-class ob)
                       ob-type)
         method-defs (method-defs-memoized ob-type)]

     (wrapper ob-type
              (instance-gc {:godot/object  ob
                            :godot/type    ob-type
                            :godot/methods method-defs
                            :godot/wrapper wrapper})))))

(defrecord Vec2 [xs]
  proto/ToVariant
  (->variant [_]
    (godot/vector2->variant (godot/new-vector2 xs))))

(defrecord Vec3 [xs]
  proto/ToVariant
  (->variant [_]
    (godot/vector3->variant (godot/new-vector3 xs))))

(defn vec2
  [xs]
  (->Vec2 (mapv float xs)))

(defn vec3
  [xs]
  (->Vec3 (mapv float xs)))

(defn vec2-xs
  [v]
  (:xs v))

(defn vec2?
  [v]
  (instance? Vec2 v))

(defn ->object*
  ([ptr-or-ob-type]
   (if (string? ptr-or-ob-type)
     (mapped-instance ptr-or-ob-type)
     (mapped-instance "Object" ptr-or-ob-type)))
  ([ob-type ptr]
   (mapped-instance ob-type ptr)))

(def ->object ->object*)
#_
(defmacro ->object
  ([address-or-ob-type]
   (if (string? address-or-ob-type)
     `^{:tag ~(gen-gdscript/godot-class-symbol gen-gdscript/ns-gdscript address-or-ob-type)} (->object* ~address-or-ob-type)
     `(->object* ~address-or-ob-type)))
  ([ob-type address]
   `^{:tag ~(gen-gdscript/godot-class-symbol gen-gdscript/ns-gdscript ob-type)} (->object* ~ob-type ~address)))

;; (vary-meta `(mapped-instance ~ob-type (proto/->ptr ~address))
;;               assoc (gen-gdscript/godot-class-symbol gen-gdscript/ns-gdscript ob-type) true)


(extend-type Variant
  proto/ToClojure
  (->clj [this]
    (let [variant (.variant this)]
      (case (.variant-type this)
        :godot-variant-type-real    (godot/variant->real variant)
        :godot-variant-type-int     (godot/variant->int variant)
        :godot-variant-type-string  (godot/variant->str variant)
        :godot-variant-type-object  (mapped-instance "Object" (godot/variant->object variant))
        :godot-variant-type-vector2 (godot/->Vector2 (godot/variant->vector2 variant))
        :godot-variant-type-vector3 (godot/->Vector3 (godot/variant->vector3 variant))))))
