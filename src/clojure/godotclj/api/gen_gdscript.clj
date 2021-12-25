(ns godotclj.api.gen-gdscript
  (:require [camel-snake-kebab.core :as csk]
            [clojure.java.io :as io]
            [backtick :refer [template]]
            [clojure.walk :as walk]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [com.stuartsierra.dependency :as dep]
            [fipp.clojure :refer [pprint]]
            ))


(defonce api-json
  (delay (walk/keywordize-keys (json/read-str (slurp (or (io/resource "api.json")
                                                         (io/resource "godot-headers/api.json")
                                                         (io/file "godot-headers/api.json")))))))

(defn hyphenate
  [s]
  (str/replace s "_" "-"))

(defn method-name->keyword
  [n]
  (keyword (hyphenate n)))

(defn ob-def
  [ob-name]
  (->> @api-json
       (filter (comp #{ob-name} :name))
       first))

(defn ob-methods
  [ob-name & {:keys [recursive] :or {recursive true}}]
  (let [ob      (ob-def ob-name)
        base    (:base_class ob)
        methods (concat (when (and (seq base) recursive)
                          (ob-methods base))
                        (:methods ob))]
    methods))

(defn index-by
  [id-fn coll]
  (into {} (map (fn [el]
                  [(id-fn el) el])
                coll)))

(defn topo-sort-by
  [coll id-fn parents-id-fn]
  (->> (map id-fn coll)
       (reduce (fn [g child-id]
                 (reduce (fn [g parent-id]
                           (dep/depend g child-id parent-id))
                         g
                         (parents-id-fn child-id)))
               (dep/graph))
       (dep/topo-sort)
       (remove nil?)
       (map (index-by id-fn coll))))


(defn order-ob-type-map
  [m]
  (remove nil? (topo-sort-by m
                             first
                             (fn [child-id]
                               (drop 1 (take-while (complement nil?) (iterate #(:base_class (ob-def %)) child-id)))))))

(defn order-ob-type-coll
  [coll]
  (remove nil? (topo-sort-by coll
                             :name
                             (fn [child-id]
                               (drop 1 (take-while (complement nil?) (iterate #(:base_class (ob-def %)) child-id)))))))

(defn ob-methods-grouped
  [ob-name & {:keys [recursive] :or {recursive true}}]
  (let [ob      (ob-def ob-name)
        base    (:base_class ob)]
    (merge (when (and (seq base) recursive)
              (ob-methods-grouped base))
           {ob-name (:methods ob)})))

(defn remove-duplicate-methods
  [type-methods]
  (reverse (first (reduce (fn [[m all-method-names] [ob-type methods]]
                            [(conj m [ob-type (remove (fn [method]
                                                        (all-method-names (:name method)))
                                                      methods)])
                             (reduce conj all-method-names (map :name methods))])
                          [[] #{}]
                          (reverse type-methods)))))

(defn argument-variations
  [method arguments]
  (let [[fixed variable] [(take-while (complement :has_default_value) arguments)
                          (drop-while (complement :has_default_value) arguments)]]
    (concat (map #(concat fixed %)
                 (map #(take % variable) (range (inc (count variable)))))
            (when (:has_varargs method)
              (for [i (range 1 6)]
                (reduce conj
                        (vec fixed)
                        (map (fn [j] {:name (str "v" j)})
                             (range 1 (inc i)))))))))

(def ns-gdscript "godotclj.api.gdscript")

(def class-prefix "Godot")
(def interface-prefix "IGodot")
(def mixin-prefix "MGodot")

(defn godot-class-symbol
  ([ob-type]
   (symbol (str class-prefix ob-type)))
  ([ns ob-type]
   (symbol (str ns "." class-prefix ob-type))))

(defn godot-mixin-symbol
  ([ob-type]
   (symbol (str mixin-prefix ob-type)))
  ([ns ob-type]
   {:pre [ns (seq (name ob-type))]}
   (symbol (str ns "." mixin-prefix ob-type))))

(defn godot-interface-symbol
  ([ob-type]
   (symbol (str interface-prefix ob-type)))
  ([ns ob-type]
   {:pre [ns (seq (name ob-type))]}
   (symbol (str ns "." interface-prefix ob-type))))

(defn godot-return-type-symbol
  ([api ob-type]
   (let [ob-type (name ob-type)]
     (cond (get api ob-type)
           (godot-interface-symbol ns-gdscript ob-type)

           (str/starts-with? ob-type "enum")
           'int

           :else
           (case ob-type
             "int"              'int
             "float"            'float
             "void"             'void
             "bool"             'boolean
             "PoolStringArray"  'godotclj.bindings.godot.IndexedPoolStringArray
             "Variant"          'godotclj.bindings.godot.Variant
             "String"           'String
             "Array"            'godotclj.bindings.godot.IndexedArray
             "Color"            'godotclj.bindings.godot.Color
             "PoolVector2Array" 'godotclj.bindings.godot.IndexedPoolVector2Array
             "Rect2"            'godotclj.bindings.godot.IndexedRect2
             "Vector2"          'godotclj.bindings.godot.Vector2
             "NodePath"         'godotclj.bindings.godot.NodePath
             "PoolByteArray"    'godotclj.bindings.godot.PoolByteArray
             "AABB"             'godotclj.bindings.godot.AABB
             "Transform"        'godotclj.bindings.godot.Transform
             "Dictionary"       'godotclj.bindings.godot.Dictionary
             "PoolIntArray"     'godotclj.bindings.godot.PoolIntArray
             "RID"              'godotclj.bindings.godot.RID
             "PoolColorArray"   'godotclj.bindings.godot.PoolColorArray
             "PoolRealArray"    'godotclj.bindings.godot.PoolRealArray
             "Vector3"          'godotclj.bindings.godot.Vector3
             "Plane"            'godotclj.bindings.godot.Plane
             "Transform2D"      'godotclj.bindings.godot.Transform2D
             "PoolVector3Array" 'godotclj.bindings.godot.PoolVector3Array
             "Basis"            'godotclj.bindings.godot.Basis)))))

(defn gen-deftype
  [{ob-type :name methods :methods :as ob}]
  (let [m (gensym "m")]
    (template
     (deftype ~(godot-class-symbol ob-type) [~m]
       proto/ToVariant
       (->variant
         [this]
         (proto/->variant ~m))
       clojure.lang.ILookup
       (valAt [_ k]
         (get ~m k))
       ~(godot-interface-symbol ns-gdscript ob-type)
       ~@(reduce concat
           (for [[ob-type methods] (remove-duplicate-methods (order-ob-type-map (ob-methods-grouped ob-type)))
                 :let              [methods (remove (comp #{"to_string"} :name) methods)]]
             (template [#_ ~(godot-mixin-symbol ob-type)
                        ~@(for [{method-name :name
                                 arguments   :arguments
                                 return-type :return_type
                                 :as         method} methods
                                arguments            (argument-variations method arguments)
                                :when                (not (str/starts-with? method-name "_"))]
                            (let [args (map symbol (map :name arguments))
                                  result `(~(csk/->camelCaseSymbol method-name  :separator \_)
                                           [~'_ ~@args]
                                           ((get ~m ~(method-name->keyword method-name)) ~@args))]

                              result))])))))))

(defn gen-definterface
  [api {ob-type :name methods :methods base-class :base_class :as ob}]
  (template (definterface ~(godot-mixin-symbol ob-type)
              ~@(for [{method-name :name
                       arguments   :arguments
                       return-type :return_type
                       :as method} methods
                      arguments    (argument-variations method arguments)
                      :when        (not (str/starts-with? method-name "_"))]
                  (let [args (map symbol (map :name arguments))]
                    `(~(vary-meta (csk/->camelCaseSymbol method-name  :separator \_)
                                  assoc :tag (godot-return-type-symbol api return-type))
                      [~@args]))))))

(defn base-classes
  [api {ob-type :name methods :methods base-class :base_class :as ob}]
  (cons ob-type
        (when (seq base-class)
          (base-classes api (get api base-class)))))

(defn base-interfaces
  [ob-type]
  (let [api (index-by :name @api-json)]
    (map #(godot-mixin-symbol ns-gdscript %) (base-classes api (get api ob-type)))))

(defn gen-geninterface
  [{ob-type :name methods :methods base-class :base_class :as ob}]
  (let [api        (index-by :name @api-json)
        interfaces (base-classes api ob)]
    (template (gen-interface
               :name ~(godot-interface-symbol ns-gdscript ob-type)
               :extends [~(godot-mixin-symbol ns-gdscript ob-type) ~@(when (seq base-class)
                                                                       [(godot-interface-symbol ns-gdscript base-class)])]))))

(defn api
  []
  (let [api (index-by :name @api-json)]
    (for [{ob-type :name methods :methods base-class :base_class :as ob} (order-ob-type-coll @api-json)
          :let [methods (remove (comp #{"to_string"} :name) methods)]]
      (let [namespace-name (csk/->kebab-case-symbol ob-type :separator \-)
            ns-sym         (csk/->kebab-case-symbol ob-type :separator \-)]
        {:namespace-name namespace-name
         :require        (template [~(csk/->kebab-case-symbol (str ns-gdscript "." ob-type) :separator \-)
                                    :as
                                    ~ns-sym])
         :deftype        (gen-deftype ob)

         :definterface   (gen-definterface api ob)

         :gen-interface  (gen-geninterface ob)

         :forms          (for [{method-name :name arguments :arguments} methods]
                           (let [args (map symbol (map :name arguments))]
                             (template
                              {:def     (def ~(csk/->kebab-case-symbol (str ob-type "_" method-name)  :separator \_)
                                          ~(symbol (csk/->kebab-case-string ob-type :separator \-)
                                                   (csk/->kebab-case-string method-name  :separator \_)))
                               :defn    (defn ~(csk/->kebab-case-symbol method-name  :separator \_)
                                          [ob ~@args]
                                          ((get ob ~(method-name->keyword method-name)) ~@args))})))}))))

(defn api-instance
  []
  (template (defn ->instance
              [ob-type m]
              (case ob-type
                ~@(reduce concat
                          (for [{ob-type :name methods :methods} @api-json]
                            (let [typename (name (godot-class-symbol ob-type))]
                              [ob-type (template (~(symbol (str "->" typename)) m))])))))))

(defn gen-api-1
  []
  (doseq [{:keys [namespace-name forms]} (godotclj.api.gen-gdscript/api)]
    (spit (doto (io/file (format "src/clojure/godotclj/api/gdscript/%s.clj" (csk/->snake_case_string namespace-name :separator \-)))
            (io/make-parents))
          (with-out-str
            (clojure.pprint/pprint (template (ns ~(symbol (str "godotclj.api.gdscript." namespace-name)))))
            (doseq [form forms]
              (clojure.pprint/pprint (:defn form))))))
  (spit (doto (io/file (format "src/clojure/godotclj/api/gdscript.clj"))
          (io/make-parents))
        (with-out-str
          (clojure.pprint/pprint (template (ns ~(symbol "godotclj.api.gdscript")
                                             (:require ~@(map :require (godotclj.api.gen-gdscript/api))))))
          (doseq [{:keys [namespace-name forms] :as ns} (godotclj.api.gen-gdscript/api)]
            (doseq [form forms]
              (clojure.pprint/pprint (:def form)))))))

(defn gen-api
  []
  (spit (doto (io/file (format "src/clojure/godotclj/api/gdscript.clj"))
          (io/make-parents))
        (binding [*print-length* nil
                  *print-meta*   true]
          (with-out-str
            (pprint (template (ns ~(symbol "godotclj.api.gdscript")
                                (:require [godotclj.proto :as proto]
                                          [godotclj.bindings.godot])
                                (:import godotclj.bindings.godot.IndexedPoolStringArray
                                         godotclj.bindings.godot.Variant
                                         godotclj.bindings.godot.IndexedArray
                                         godotclj.bindings.godot.Color
                                         godotclj.bindings.godot.IndexedPoolVector2Array
                                         godotclj.bindings.godot.IndexedRect2
                                         godotclj.bindings.godot.Vector2
                                         godotclj.bindings.godot.NodePath
                                         godotclj.bindings.godot.PoolByteArray
                                         godotclj.bindings.godot.AABB
                                         godotclj.bindings.godot.Transform
                                         godotclj.bindings.godot.Dictionary
                                         godotclj.bindings.godot.PoolIntArray
                                         godotclj.bindings.godot.RID
                                         godotclj.bindings.godot.PoolColorArray
                                         godotclj.bindings.godot.PoolRealArray
                                         godotclj.bindings.godot.Vector3
                                         godotclj.bindings.godot.Plane
                                         godotclj.bindings.godot.Transform2D
                                         godotclj.bindings.godot.PoolVector3Array
                                         godotclj.bindings.godot.Basis))))
            (doseq [ns (godotclj.api.gen-gdscript/api)]
              (pprint (:definterface ns)))
            (doseq [ns (godotclj.api.gen-gdscript/api)]
              (pprint (:gen-interface ns)))
            (doseq [ns (godotclj.api.gen-gdscript/api)]
              (pprint (:deftype ns)))
            (pprint (api-instance))))))

(comment
  (binding [*print-meta* true]
    (let [api (index-by :name @api-json)]
      (pprint (gen-definterface api (get api "Reference")))))

  (binding [*print-meta* true]
    (let [api (index-by :name @api-json)]
      (pprint (gen-definterface api (get api "Object")))))

  (binding [*print-meta* true]
    (let [api (index-by :name @api-json)]
      (doseq [[_ v] api]
        (gen-definterface api v))))

  (binding [*print-meta* true]
    (let [api (index-by :name @api-json)]
      (pprint (gen-definterface api (get api "_Engine")))))

  (binding [*print-meta* true]
    (let [api (index-by :name @api-json)]
      (pprint (gen-definterface api (get api "Dictionary")))))

  (let [api (index-by :name @api-json)]
    (pprint (gen-geninterface (get api "Reference"))))

  (let [api (index-by :name @api-json)]
    (pprint (gen-geninterface (get api "Reference"))))

  (let [api (index-by :name @api-json)]
    (pprint (gen-deftype (get api "Reference"))))

  )
