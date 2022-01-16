(ns godotclj.bindings.godot
  (:require [godotclj.ffi.gdnative :as gdnative]
            [tech.v3.datatype.struct :as dtype-struct]
            [tech.v3.datatype.protocols]
            [tech.v3.datatype.ffi :as dtype-ffi]
            [tech.v3.datatype :as dtype]
            [godotclj.proto :as proto]
            [tech.v3.datatype.native-buffer :as native-buffer]
            [tech.v3.datatype.struct :as dtype-struct])
  (:import [tech.v3.datatype.ffi Pointer]
           [clojure.lang Indexed Seqable]))

(dtype-struct/define-datatype! :method-reference
  [{:name :id :datatype :int64}])

(defn new-struct
  [datatype]
  (dtype-struct/new-struct datatype {:container-type :native-heap}))

(defn create_func_callback
  [p-args]
  (println :clj_create_func_callback)
  )

(defn destroy_func_callback
  [p-args]
  (println :clj_destroy_func_callback)
  )

(defn free_func_callback
  [p-args]
  (println :clj_free_func_callback))

(defonce method-registry-agent
  (agent {:methods {}
          :next-id 0}))

(defn property_set_func_callback
  [p-args]
  (let [{:keys [ob data method-data variant] :as args} (into {} (dtype-ffi/ptr->struct :property-setter-func-args (Pointer. p-args)))
        f                                              (get-in @method-registry-agent [:methods (:id (dtype-ffi/ptr->struct :method-reference (Pointer. method-data)))])]
    (if f
      (f ob method-data data variant)
      (println :f-not-found))))

(defn property_get_func_callback
  [p-args]
  (println :clj_project_get_func_callback))

(defonce print-agent
  (agent 0))

(defn print-at-agent
  [counter & args]
  (inc counter))

(defn instance_method_callback
  [arg-ptr]
  (let [arg-ptr                                              (Pointer. arg-ptr)
        {:keys [ob method-data user-data nargs args result]} (into {} (dtype-ffi/ptr->struct :instance-method-callback-args arg-ptr))
        f                                                    (get-in @method-registry-agent [:methods (:id (dtype-ffi/ptr->struct :method-reference (Pointer. method-data)))])
        data                                                 (get-in @method-registry-agent [:method-info (:id (dtype-ffi/ptr->struct :method-reference (Pointer. method-data)))])]
    (send print-agent print-at-agent :+instance_method_callback data)
    (await print-agent)
    (if f
      (f ob method-data user-data nargs args)
      (println :f-not-found))

    (send print-agent print-at-agent :-instance_method_callback data)
    (await print-agent))

  nil
  )

(defn method-register
  [{:keys [next-id] :as index} ^java.util.Map method-data f data]
  (try
    (.put method-data :id next-id)
    (-> index
        (assoc-in [:methods next-id] f)
        ;; for debugging
        (assoc-in [:method-info next-id] data)
        ;; to maintain to prevent GC
        (assoc-in [:method-data next-id] method-data)
        (update :next-id inc))
    (catch Exception e
      (println e)
      (throw e))))

;; ---------------

(defn register-class
  [p-handle class-name parent-class-name create-fn destroy-fn]
  (let [create  (dtype-struct/new-struct :godot-instance-create-func {:container-type :native-heap})
        destroy (dtype-struct/new-struct :godot-instance-destroy-func {:container-type :native-heap})]

    (gdnative/get_godot_instance_create_func (dtype-ffi/->pointer create))
    (gdnative/get_godot_instance_destroy_func (dtype-ffi/->pointer destroy))

    ;; TODO create-fn/destroy-fn

    (gdnative/godot_nativescript_register_class_wrapper p-handle
                                                        (dtype-ffi/string->c class-name)
                                                        (dtype-ffi/string->c parent-class-name)

                                                        (dtype-ffi/->pointer create)
                                                        (dtype-ffi/->pointer destroy))))

(defn register-property
  [p-handle class-name property-path set-fn get-fn & {:keys [value type] :or {value 0.0
                                                                              type :real}}]
  (let [attributes  (dtype-struct/new-struct :godot-property-attributes {:container-type :native-heap})
        setter      (dtype-struct/new-struct :godot-property-set-func {:container-type :native-heap})
        getter      (dtype-struct/new-struct :godot-property-get-func {:container-type :native-heap})
        method-data (dtype-struct/new-struct :method-reference {:container-type :native-heap})]

    (send method-registry-agent method-register method-data set-fn {:property [class-name property-path]})
    (await method-registry-agent)

    ;; TODO enums
    (.put attributes :type
          (or (cond (boolean? value) 1
                    (int? value)     2
                    (float? value)   3
                    (string? value)  4)
              (case type
                :real 3
                :packed-scene 17))
          ) ;; string = 4, int = 2 (see variant.h), bool = 1
    (.put attributes :usage (bit-or 1 2 4))

    ;; TODO default value

    (gdnative/get_godot_property_set_func (dtype-ffi/->pointer setter))
    (gdnative/get_godot_property_get_func (dtype-ffi/->pointer getter))

    (.put setter :method-data (.address (dtype-ffi/->pointer method-data)))

    ;; TODO getter

    (gdnative/godot_nativescript_register_property_wrapper p-handle
                                                           (dtype-ffi/string->c class-name)
                                                           (dtype-ffi/string->c property-path)
                                                           (dtype-ffi/->pointer attributes)

                                                           (dtype-ffi/->pointer setter)
                                                           (dtype-ffi/->pointer getter))))

(defn register-method
  [p-handle class-name method-name f]
  (let [method      (dtype-struct/new-struct :godot-instance-method {:container-type :native-heap})
        attributes  (dtype-struct/new-struct :godot-method-attributes {:container-type :native-heap})
        method-data (dtype-struct/new-struct :method-reference {:container-type :native-heap})]

    (send method-registry-agent method-register method-data f {:method [class-name method-name]})
    (await method-registry-agent)

    ;; TODO this is really only need once to create a template. reuse?
    (gdnative/get_godot_instance_method (dtype-ffi/->pointer method))

    (.put method :method-data (.address (dtype-ffi/->pointer method-data)))

    ;; TODO enumbs
    (.put attributes :rpc-type 0)

    (gdnative/godot_nativescript_register_method_wrapper p-handle
                                                         (dtype-ffi/string->c class-name)
                                                         (dtype-ffi/string->c method-name)
                                                         (dtype-ffi/->pointer attributes)
                                                         (dtype-ffi/->pointer method))))

(defn str->godot-string ^java.util.Map
  [s]
  (let [result ^java.util.Map (new-struct :godot-string)]
    (gdnative/godot_string_new_wrapper (dtype-ffi/->pointer result))
    (gdnative/godot_string_parse_utf8_wrapper (dtype-ffi/->pointer result)
                                              (dtype-ffi/string->c s))
    result))

(defn register-signal
  [p-handle class-name signal-name]
  (let [signal ^java.util.Map (new-struct :godot-signal)]
    (.put signal :name (str->godot-string signal-name))

    (gdnative/godot_nativescript_register_signal_wrapper p-handle
                                                         (dtype-ffi/string->c class-name)
                                                         (dtype-ffi/->pointer signal))))

(defn get-class-constructor
  [class-name]
  (let [constructor-wrapper (dtype-struct/new-struct :godot-class-constructor-wrapper {:container-type :native-heap})]
    (gdnative/godot_get_class_constructor_wrapper (dtype-ffi/string->c class-name)
                                                  (dtype-ffi/->pointer constructor-wrapper))

    (fn []
      (let [f (proto/->function (:value constructor-wrapper)
                                :pointer)]
        (f)))))

(defn construct
  [class-name]
  (let [f (get-class-constructor class-name)]
    (f)))

(defn godot-string->str
  [godot-string]
  (let [char-string   (dtype-struct/new-struct :godot-char-string {:container-type :native-heap})]
    (gdnative/godot_string_ascii_wrapper (dtype-ffi/->pointer godot-string)
                                         (dtype-ffi/->pointer char-string))

    (dtype-ffi/c->string (gdnative/godot_char_string_get_data_wrapper (dtype-ffi/->pointer char-string)))))

(defn node-path->godot-string
  [node-path]
  (let [godot-string   (dtype-struct/new-struct :godot-string {:container-type :native-heap})]
    (gdnative/godot_node_path_as_string_wrapper (dtype-ffi/->pointer node-path)
                                                (dtype-ffi/->pointer godot-string))
    godot-string))

(defn variant->str
  [string-variant]
  (let [string        (dtype-struct/new-struct :godot-string {:container-type :native-heap})
        char-string   (dtype-struct/new-struct :godot-char-string {:container-type :native-heap})]

    (gdnative/godot_variant_as_string_wrapper (dtype-ffi/->pointer string-variant)
                                              (dtype-ffi/->pointer string))

    (gdnative/godot_string_ascii_wrapper (dtype-ffi/->pointer string)
                                         (dtype-ffi/->pointer char-string))

    (dtype-ffi/c->string (gdnative/godot_char_string_get_data_wrapper (dtype-ffi/->pointer char-string)))))

(defn indexed-seq-from
  [this i]
  (when (< i (count this))
    (lazy-seq (cons (nth this i)
                    (indexed-seq-from this (inc i))))))

(defn pool-string-array-get
  [coll i]
  (let [result (new-struct :godot-string)]
    (gdnative/godot_pool_string_array_get_wrapper (dtype-ffi/->pointer coll)
                                                  i
                                                  (dtype-ffi/->pointer result))
    (godot-string->str result)))

(defn pool-string-array-size
  [coll]
  (gdnative/godot_pool_string_array_size_wrapper (dtype-ffi/->pointer coll)))

(deftype IndexedPoolStringArray [coll]
  Indexed
  (nth [_ i]
    (pool-string-array-get coll i))

  (nth [_ i notFound]
    (if (< i (pool-string-array-size coll))
      (pool-string-array-get coll i)
      notFound))

  (count [_]
    (pool-string-array-size coll))

  Seqable
  (seq [this]
    (indexed-seq-from this 0)))

(defn pool-string-array->indexed
  [coll]
  (->IndexedPoolStringArray coll))

(defn vector2-x
  [v]
  (gdnative/godot_vector2_get_x_wrapper (dtype-ffi/->pointer v)))

(defn vector2-y
  [v]
  (gdnative/godot_vector2_get_x_wrapper (dtype-ffi/->pointer v)))

(defn vector2-nth
  ([v i]
   (vector2-nth v i nil))
  ([v i not-found]
   (case i
     0 (vector2-x v)
     1 (vector2-y v)
     not-found)))

(defn vector3-nth
  ([v i]
   (vector3-nth v i nil))
  ([v i not-found]
   (gdnative/godot_vector3_get_axis_wrapper (dtype-ffi/->pointer v)
                                            i)))

(defn vector2->variant
  [vector2]
  (let [variant (dtype-struct/new-struct :godot-variant {:container-type :native-heap})]
    (gdnative/godot_variant_new_vector2_wrapper (dtype-ffi/->pointer variant)
                                                (dtype-ffi/->pointer vector2))
    variant))

(defn vector3->variant
  [vector3]
  (let [variant (dtype-struct/new-struct :godot-variant {:container-type :native-heap})]
    (gdnative/godot_variant_new_vector3_wrapper (dtype-ffi/->pointer variant)
                                                (dtype-ffi/->pointer vector3))
    variant))

(deftype Vector2 [v]
  tech.v3.datatype.protocols/PToNativeBuffer
  (convertible-to-native-buffer? [_]
    (tech.v3.datatype.protocols/convertible-to-native-buffer? v))
  (->native-buffer [_]
    (tech.v3.datatype.protocols/->native-buffer v))

  proto/ToVariant
  (->variant [this]
    (vector2->variant v))

  Indexed
  (nth [_ i]
    (vector2-nth v i))

  (nth [_ i not-found]
    (vector2-nth v i not-found))

  (count [_]
    2)

  Seqable
  (seq [this]
    (indexed-seq-from this 0)))

(deftype Vector3 [v]
  tech.v3.datatype.protocols/PToNativeBuffer
  (convertible-to-native-buffer? [_]
    (tech.v3.datatype.protocols/convertible-to-native-buffer? v))
  (->native-buffer [_]
    (tech.v3.datatype.protocols/->native-buffer v))

  proto/ToVariant
  (->variant [this]
    (vector3->variant v))

  Indexed
  (nth [_ i]
    (vector3-nth v i))

  (nth [_ i not-found]
    (vector3-nth v i not-found))

  (count [_]
    3)

  Seqable
  (seq [this]
    (indexed-seq-from this 0)))

(defn rect2->size
  [rect2]
  (let [result (new-struct :godot-variant)]
    (gdnative/godot_rect2_get_size_wrapper (dtype-ffi/->pointer rect2)
                                           (dtype-ffi/->pointer result))
    result))

(deftype IndexedRect2 [rect2]
  Indexed
  (nth [_ i]
    (nth (->Vector2 (rect2->size rect2)) i))

  (nth [_ i notFound]
    (nth (->Vector2 (rect2->size rect2)) i notFound))

  (count [_]
    (count (->Vector2 (rect2->size rect2))))

  Seqable
  (seq [this]
    (seq (->Vector2 (rect2->size rect2)))))

(defn rect2->indexed
  [rect2]
  (->IndexedRect2 rect2))

(defn variant->wrapper
  [v & {:keys [datatype]
        ;; what is with a nil default?
        :or   {datatype nil}}]
  (let [[wrapped? f] (case datatype
                       :godot-array             [true gdnative/godot_variant_as_array_wrapper]
                       :godot-pool-string-array [true gdnative/godot_variant_as_pool_string_array_wrapper]
                       :godot-rect2             [true gdnative/godot_variant_as_rect2_wrapper]
                       :godot-vector2           [true gdnative/godot_variant_as_vector2_wrapper]
                       :godot-vector3           [true gdnative/godot_variant_as_vector3_wrapper]
                       :godot-bool              [false (comp #(not= % 0) gdnative/godot_variant_as_bool_wrapper)]
                       :godot-real              [false gdnative/godot_variant_as_real_wrapper]
                       :int64_t                 [false gdnative/godot_variant_as_int_wrapper]
                       :godot-object            [false gdnative/godot_variant_as_object_wrapper]
                       :godot-node-path         [true gdnative/godot_variant_as_node_path_wrapper])
        result       (when wrapped?
                       (dtype-struct/new-struct datatype {:container-type :native-heap}))]
    (let [ret (apply f
                     (dtype-ffi/->pointer v)
                     (when result
                       [(dtype-ffi/->pointer result)]))]
      (or result ret))))

(def variant->array #(variant->wrapper % :datatype :godot-array))
(def variant->pool-string-array #(variant->wrapper % :datatype :godot-pool-string-array))
(def variant->rect2 #(variant->wrapper % :datatype :godot-rect2))
(def variant->vector2 #(variant->wrapper % :datatype :godot-vector2))
(def variant->vector3 #(variant->wrapper % :datatype :godot-vector3))
(def variant->bool #(variant->wrapper % :datatype :godot-bool))
(def variant->real #(variant->wrapper % :datatype :godot-real))
(def variant->int #(variant->wrapper % :datatype :int64_t))
(def variant->object #(variant->wrapper % :datatype :godot-object))
(def variant->node-path #(variant->wrapper % :datatype :godot-node-path))

(defn get-variant-type
  [v]
  (let [result (gdnative/godot_variant_get_type_wrapper v)]
    (get-in gdnative/enums [:godot-variant-type result])))

(defrecord Variant [variant variant-type])

(defn new-variant
  ([variant]
   (let [ptr          (dtype-ffi/->pointer variant)]
     (new-variant variant (get-variant-type ptr))))
  ([variant variant-type]
   (->Variant variant variant-type)))





(defn object->variant
  [ob]
  (let [variant (dtype-struct/new-struct :godot-variant {:container-type :native-heap})]
    (gdnative/godot_variant_new_object_wrapper (dtype-ffi/->pointer variant)
                                               ob)
    variant))

(defn array->variant
  [children]
  (let [arr (new-struct :godot-array)
        v   (new-struct :godot-variant)]

    (gdnative/godot_array_new_wrapper (dtype-ffi/->pointer arr))

    (doseq [child children]
      (let [v2 (proto/->variant child)]
        (gdnative/godot_array_append_wrapper (dtype-ffi/->pointer arr)
                                             (dtype-ffi/->pointer v2))))
    (gdnative/godot_variant_new_array_wrapper (dtype-ffi/->pointer v)
                                              (dtype-ffi/->pointer arr))
    v))

(defn str->variant
  [s]
  (let [title   (dtype-struct/new-struct :godot-string {:container-type :native-heap})
        variant (dtype-struct/new-struct :godot-variant {:container-type :native-heap})]
    (gdnative/godot_string_new_wrapper (dtype-ffi/->pointer title))
    (let [result (gdnative/godot_string_parse_utf8_wrapper (dtype-ffi/->pointer title)
                                                           (dtype-ffi/string->c s))]
      (gdnative/godot_variant_new_string_wrapper (dtype-ffi/->pointer variant)
                                                 (dtype-ffi/->pointer title)))

    variant))

(defn ->godot-bool
  [b]
  (if b 1 0))

(defn ->godot-int
  [i]
  i)

(defn ->godot-real
  [i]
  (float i))

(defn bool->variant
  [s]
  (let [result (new-struct :godot-variant)]
    (gdnative/godot_variant_new_bool_wrapper (dtype-ffi/->pointer result)
                                             (->godot-bool s))
    result))

(defn int->variant
  [i]
  (let [result (new-struct :godot-variant)]
    (gdnative/godot_variant_new_int_wrapper (dtype-ffi/->pointer result)
                                            (->godot-int i))
    result))

(defn long->variant
  [i]
  (int->variant i))

(defn double->variant
  [i]
  (let [result (new-struct :godot-variant)]
    (gdnative/godot_variant_new_real_wrapper (dtype-ffi/->pointer result)
                                             (->godot-real i))
    result))

(defn call-wrapper
  [result-datatype f v]
  (let [result (dtype-struct/new-struct result-datatype {:container-type :native-heap})]
    (f (dtype-ffi/->pointer v)
       (dtype-ffi/->pointer result))

    result))

(defn array-size
  [xs]
  (gdnative/godot_array_size_wrapper xs))

(defn array-get
  [v i]
  (let [result (dtype-struct/new-struct :godot-variant {:container-type :native-heap})]
    (gdnative/godot_array_get_wrapper (dtype-ffi/->pointer v)
                                      i
                                      (dtype-ffi/->pointer result))
    result))

(defn array->seq
  [v]
  (vec
   (for [i (range (array-size v))]
     (->> (array-get v i)
          ;; TODO different variant types
          variant->object))))

(defn get-class
  [ob]
  (let [method (gdnative/godot_method_bind_get_method_wrapper
                (dtype-ffi/string->c "Object")
                (dtype-ffi/string->c "get_class"))]

    (let [class-variant (dtype-struct/new-struct :godot-variant {:container-type :native-heap})
          string        (dtype-struct/new-struct :godot-string {:container-type :native-heap})
          char-string   (dtype-struct/new-struct :godot-char-string {:container-type :native-heap})]
      (gdnative/godot_method_bind_call_wrapper method
                                               ob
                                               nil
                                               0
                                               nil
                                               (dtype-ffi/->pointer class-variant))
      (gdnative/godot_variant_as_string_wrapper (dtype-ffi/->pointer class-variant)
                                                (dtype-ffi/->pointer string))

      (gdnative/godot_string_ascii_wrapper (dtype-ffi/->pointer string)
                                           (dtype-ffi/->pointer char-string))

      (dtype-ffi/c->string (gdnative/godot_char_string_get_data_wrapper (dtype-ffi/->pointer char-string))))))

(defn indexed-variant-array-get
  ([ptrs i]
   (indexed-variant-array-get ptrs i nil))
  ([ptrs i not-found]
   (when (< i (count ptrs)))
   (let [ptr          (Pointer. (nth ptrs i))
         variant      (dtype-ffi/ptr->struct :godot-variant ptr)]
     (proto/->clj (new-variant variant (get-variant-type ptr))))))

(deftype IndexedVariantArray [ptrs]
  Indexed
  (nth [_ i]
    (indexed-variant-array-get ptrs i))

  (nth [_ i not-found]
    (if (< i (count ptrs))
      (indexed-variant-array-get ptrs i)
      not-found))

  (count [_]
    (count ptrs))

  Seqable
  (seq [this]
    (indexed-seq-from this 0)))

(defn ->indexed-variant-array
  [n-args p-args]
  (let [buf       (native-buffer/wrap-address p-args (* n-args 8) nil)
        addresses (mapv #(native-buffer/read-long buf (* % 8)) (range n-args))]
    (->IndexedVariantArray addresses)))

(defn new-vector2
  [vs]
  (let [v (new-struct :godot-vector2)]
    (gdnative/godot_vector2_new_wrapper (dtype-ffi/->pointer v)
                                        (float (vs 0))
                                        (float (vs 1)))
    v))

(defn new-vector3
  [vs]
  (let [v (new-struct :godot-vector3)]
    (gdnative/godot_vector3_new_wrapper (dtype-ffi/->pointer v)
                                        (float (vs 0))
                                        (float (vs 1))
                                        (float (vs 2)))
    v))



(extend-type java.lang.Double
  proto/ToClojure
  (->clj [value] value)
  proto/ToVariant
  (->variant [value] (double->variant value)))

(extend-type java.lang.String
  proto/ToVariant
  (->variant [s] (str->variant s))
  proto/ToClojure
  (->clj [s] s))

(extend-type java.lang.Boolean
  proto/ToVariant
  (->variant [b] (bool->variant b)))

(extend-type java.lang.Integer
  proto/ToVariant
  (->variant [b] (int->variant b)))

(extend-type java.lang.Long
  proto/ToVariant
  (->variant [b] (long->variant b))
  proto/ToClojure
  (->clj
    [i]
    ;; TODO these seem redundant
    i)
  proto/PVariantToObject
  (pvariant->object [value]
    (variant->object (dtype-ffi/ptr->struct :godot-variant (Pointer. value)))))

(extend-type clojure.lang.PersistentVector
  proto/ToVariant
  (->variant [xs] (array->variant xs)))

(extend-type Pointer
  proto/ToVariant
  (->variant [p] (dtype-ffi/ptr->struct :godot-variant p)))

(defn vector2-rotated
  [v phi]
  (let [result (new-struct :godot-vector2)]
    (gdnative/godot_vector2_rotated_wrapper (dtype-ffi/->pointer v)
                                            phi
                                            (dtype-ffi/->pointer result))
    result))

(deftype IndexedArray [m object]
  dtype-ffi/PToPointer
  (convertible-to-pointer? [item] true)
  (->pointer [this]
    (dtype-ffi/->pointer object)))

(deftype Color [])
(deftype IndexedPoolVector2Array [])
(deftype NodePath [m]
  Object
  (toString [_]
    (godot-string->str (node-path->godot-string m))))

(deftype PoolByteArray [])
(deftype AABB [])
(deftype Transform [])
(deftype Dictionary [])
(deftype PoolIntArray [])
(deftype RID [])
(deftype PoolColorArray [])
(deftype PoolRealArray [])
(deftype Plane [])
(deftype Transform2D [])
(deftype PoolVector3Array [])
(deftype Basis [])

(defn register-classes
  [p-handle classes]
  (doseq [[cls {:keys [base create destroy properties methods signals]}] classes]
    (register-class p-handle cls base create destroy)
    (doseq [[property-name {property-type :type :keys [value getter setter]}] properties]
      (register-property p-handle cls property-name setter getter :type property-type :value value))
    (doseq [[method-name method-fn] methods]
      (register-method p-handle cls method-name method-fn))
    (doseq [signal-name signals]
      (register-signal p-handle cls signal-name))))
