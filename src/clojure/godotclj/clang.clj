(ns godotclj.clang
  (:require [camel-snake-kebab.core :as csk]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [cognitect.transit :as transit]
            [com.stuartsierra.dependency :as dep]
            [meander.epsilon :as m]
            [tech.v3.datatype.ffi.clang :as ffi-clang]
            [tech.v3.datatype.struct :as dtype-struct])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(defn enum-values
  [enum-inner]
  (m/match enum-inner
    [{:name !name :type !type :kind "EnumConstantDecl"} ...]
    !name))

(defn get-defs*
  [records & {:keys [name]}]
  (m/match {:name    name
            :records records}
    {:name    ?name
     :records {:inner [(m/or {:name ?name
                              :as   !gd}
                             _)
                       ...]}}
    !gd))

(defn get-def-by-id
  [records id]
  (first (m/match {:id      id
                   :records records}
           {:id      ?id
            :records {:inner [(m/or {:id ?id
                                     :as   !gd}
                                    _)
                              ...]}}
           !gd)))

(defn get-def*
  [records & {:keys [name]}]
  (first (m/match {:name    name
                   :records records}
           {:name    ?name
            :records {:inner [(m/or {:name ?name
                                     :as   !gd}
                                    _)
                              ...]}}
           !gd)))

(defn get-def
  [records & {:keys [name kind]}]
  (first (m/match {:name    name
                   :kind    kind
                   :records records}
           {:name    ?name
            :kind    ?kind
            :records {:inner [(m/or {:name ?name
                                     :kind ?kind
                                     :as   !gd}
                                    _)
                              ...]}}
           !gd)))

(defn qual-type*
  [records-json type-name]
  (-> (get-def records-json :name type-name :kind "TypedefDecl") :type :qualType))

(defn split-type
  [t]
  (str/split t #"\b"))

(defn qual-type
  [records-json type-name]
  (let [value (or (->> type-name
                      (iterate #(qual-type* records-json %))
                      (take-while (complement nil?))
                      last)
                  type-name)]
    (when value
      (if (#{"enum"} (first (split-type value)))
        "int"
        value))))

(defn gdnative-api-methods
  [records-json]
  (->> (get-def* records-json :name "godot_gdnative_core_api_struct")
       :inner
       (filter #(str/includes? (-> % :type :qualType) "(*)"))
       (mapv :name)))

(defn gdnative-nativescript-methods
  [records-json]
  (->> (get-def* records-json :name "godot_gdnative_ext_nativescript_api_struct")
       :inner
       (filter #(str/includes? (-> % :type :qualType) "(*)"))
       (mapv :name)))

(defn read-records
  [resource-name]
  (-> (slurp (io/resource resource-name))
      (str/split #"\n")
      (->> (partition-by #(re-find #"^.*Record Layout.*$" %))
           (remove #(re-find #"^.*Record Layout.*$" (first %))))))

(defn read-records-json
  [resource-name]
  (walk/keywordize-keys (json/read-str (slurp (io/resource resource-name)))))

(defn names
  [records]
  (m/match (:inner records)
    [(m/cata !name) ...]
    !name
    {:name !name}
    !name))

(defn get-fn-parameters
  [fn-def]
  (m/match fn-def
    {:inner [(m/or {:as !param}
                   _)
             ...]}
    !param)
  )

(defn function-def
  [records-json fn-name]
  (let [fn-def (get-def records-json :name fn-name :kind "FunctionDecl")]
    (when fn-def
      {:fn-type    (or (-> fn-def :type :desugaredQualType)
                       (-> fn-def :type :qualType))
       :parameters (-> fn-def get-fn-parameters)})))

(defn get-parameters
  [pars]
  {:pre [pars]}
  (m/match pars
    [(m/or {:kind "ParmVarDecl" :as !param}
           _)
     ...]
    !param))

(defn fn-type-def
  [records-json fn-name]
  (let [{:keys [fn-type] :as fn-def} (function-def records-json fn-name)
        pars              (get-parameters (:parameters (function-def records-json fn-name)))
        arg-types         (mapv (comp :qualType :type) pars)
        arg-names         (mapv :name pars)
        arg-list          (str "(" (str/join ", " arg-types) ")")]
    (assert fn-def "Function not found")
    (assert (str/ends-with? fn-type arg-list))
    {:name        fn-name
     :return-type (str/trim (subs fn-type 0 (- (count fn-type) (count arg-list))))
     :arg-types   arg-types
     :arg-names   arg-names}))

(defn arg-type->pointer
  [arg-type]
  (str arg-type "*"))

(defn deref-arg-name
  [arg-name]
  (str "*" arg-name))

(defn pointer-arg-type?
  [t]
  (str/includes? t "*"))

(defn primitive-arg-type?
  [records-json t]
  (and (not (pointer-arg-type? t))
       (let [parts (map (fn [t]
                          (qual-type records-json t))
                        (split-type t))]
         (some #{"int" "long" "char" "float" "double" "bool"} parts))))

(defn wrap?
  [records-json return-type]
  (or (= return-type "void")
      (pointer-arg-type? return-type)
      (primitive-arg-type? records-json return-type)))

(defn struct-type?
  [records-json t]
  (not (primitive-arg-type? records-json t)))

(defn struct-value-type?
  [records-json t]
  (and (not (primitive-arg-type? records-json t))
       (not (pointer-arg-type? t))))

(defn dereference-arg
  [records-json arg-type arg-name]
  (if (struct-value-type? records-json arg-type)
    (deref-arg-name arg-name)
    arg-name))

(defn dereference-args
  [records-json arg-types arg-names]
  (map (fn [arg-type arg-name]
         (dereference-arg records-json arg-type arg-name))
       arg-types
       arg-names))

(defn wrap-type
  [records-json arg-type]
  (if (struct-value-type? records-json arg-type)
    (arg-type->pointer arg-type)
    arg-type))

(defn wrap-types
  [records-json arg-types]
  (map (fn [arg-type]
         (wrap-type records-json arg-type))
       arg-types))

(defn struct-def
  [records-json struct-name]
  (get-def records-json :name struct-name :kind "RecordDecl"))

(defn struct-field-def
  [struct-name field-name]
  (-> (struct-def struct-name)
      (get-def :name field-name :kind "FieldDecl")))

(defn str-interp
  [s m]
  (str/replace s #"[{][{]([^}]+)[}][}]"
               (fn [match]
                 (str (get m (keyword (second match)))))))

(defn str-but-last
  [s]
  (subs s 0 (dec (count s))))

(defn wrapper-defaults
  [{:keys [return-type] :as fn-def}]
  {:name return-type})

(defn return-defaults
  [records-json {:keys [return-type] :as fn-def}]
  (if (wrap? records-json return-type)
    {:wrapped? false}
    (let [wrapper (wrapper-defaults fn-def)]
      {:wrapped?   true
       :wrapper    wrapper
       :arg-type   (str (:name wrapper) "*")
       :arg-name   "result"
       :arg-member "*result"})))

(defn api-defaults
  [{:keys [name] :as fn-def}]
  {:prefix (if (str/includes? name "nativescript")
             "nativescript_api->"
             "api->")})

(defn emit-wrapper-fn-signature
  [records-json
   {:keys [name return-type arg-names arg-types]
    :as   fn-def}
   & {:keys [suffix api return types]
      :or   {suffix "_wrapper"
             api    (api-defaults fn-def)
             return (return-defaults records-json fn-def)}}]
  {:pre [fn-def]}
  (let [{:keys [wrapped?]} return
        arg-types          (wrap-types records-json arg-types)]
    (str-interp
     (if (and wrapped? (not= return-type "void"))
       "void {{name}}{{suffix}}({{args}}, {{return-type}} {{result}})"
       "{{return-type}} {{name}}{{suffix}}({{args}})")
     {:name        name
      :suffix      suffix
      :return-type (if wrapped?
                     (:arg-type return)
                     return-type)
      :result      (:arg-name return)
      :args        (str/join ", " (map #(str/join " " %) (map vector arg-types arg-names)))
      :arg-names   (str/join ", " arg-names)})))

(defn emit-wrapper-fn-body
  [records-json {:keys [name return-type arg-names arg-types] :as fn-def} & options]
  (let [
        {:keys [suffix api return]
         :or   {api    (api-defaults fn-def)
                suffix "_wrapper"
                return (return-defaults records-json fn-def)}} options
        {:keys [wrapped?]}                        return]
    (str-interp
     (if (and wrapped? (not= return-type "void"))
       "{{return-arg-member}} = {{api}}{{name}}({{arg-names}});"
       "{{return}}{{api}}{{name}}({{arg-names}});")
     {:signature         (apply emit-wrapper-fn-signature records-json fn-def options)
      :name              name
      :suffix            suffix
      :api               (:prefix api)
      :return-type       return-type
      :return-arg-member (:arg-member return)
      :arg-names         (str/join ", " (dereference-args records-json arg-types arg-names))
      :return            (if (= return-type "void")
                           ""
                           "return ")})))

(defn indent
  [lead body]
  (str lead body))

(defn emit-wrapper-fn
  [records-json fn-def & options]
  (str-interp
   "{{signature}} {
{{body}}
}"

   {:signature   (apply emit-wrapper-fn-signature records-json fn-def options)
    :body        (indent "  " (apply emit-wrapper-fn-body records-json fn-def options))}))

(defn emit-type
  [records-json line {:keys [types] :or {types {}}}]
  (let [parts (filter seq (map str/trim (str/split line #"\b")))]
    (cond (some #(re-find #"[*]" %) parts)
          [(symbol (last parts)) :pointer?]
          :else
          (let [head (first parts)]
            [(symbol (last parts)) (cond (primitive-arg-type? records-json head)
                                         (case (or (qual-type records-json head)
                                                   head)
                                           "int"    :int32
                                           "float"  :float32
                                           "double" :float64
                                           "bool"   :int8
                                           "long"   :int64)
                                         (pointer-arg-type? head)
                                         :pointer?
                                         :else
                                         (throw (ex-info "Unknown type" {:type head})))]))))

(defn emit-type-name
  [records-json line {:keys [types] :or {types {}}}]
  (let [parts (filter seq (map str/trim (str/split line #"\b")))]
    (qual-type records-json (first parts))))

(defn emit-fn-arg-types
  [records-json {:keys [name return-type arg-names arg-types] :as fn-def} & {:keys [types]}]
  (vec
   (for [line (map #(str/join " " %) (map vector (map #(str/trim (str/replace % #"\bconst\b" "")) arg-types) arg-names))]
     (emit-type records-json line {:types types}))))

(defn emit-fn-arg-type-names
  [records-json {:keys [name return-type arg-names arg-types] :as fn-def} & {:keys [types]}]
  (for [line (map #(str/join " " %) (map vector (map #(str/trim (str/replace % #"\bconst\b" "")) arg-types) arg-names))]
    (emit-type-name records-json line {:types types})))

;; TODO this belongs in graalvm.clj, maybe
(defn type->keyword
  [t]
  (case t
    "void"                 :void
    "bool"                 :boolean
    "long"                 :int64
    "int"                  :int32
    "int32_t"              :int32
    "int16_t"              :int16
    "int64_t"              :int64
    "uint64_t"             :uint64
    "uint16_t"             :uint16
    "int8_t"               :int8
    "uint32_t"             :uint32
    ("signed char" "char") :int8
    "uint8_t"              :uint8
    "double"               :float64
    "float"                :float32))

(defn ->rettype
  [k]
  (case k
    :boolean :int8
    k))

(defn ->transit
  [data]
  (let [out (ByteArrayOutputStream.)
        w (transit/writer (io/output-stream out) :json)]
    (transit/write w data)
    (str out)))

(defn <-transit
  [^String s]
  (let [in     (ByteArrayInputStream. (.getBytes s))
        reader (transit/reader in :json)]
    (transit/read reader)))

(defn emit-fns
  [cache records-json fns]
  (let [fns (or (when (.exists (io/file cache))
                  (<-transit (slurp cache)))
                (into {}
                      (for [f fns]
                        (let [fn-name                        (if (string? f)
                                                               f
                                                               (first f))
                              {:keys [wrapped?] :as options} (if (string? f)
                                                               {:wrapped? true}
                                                               (second f))
                              definition                     (fn-type-def @records-json fn-name)]
                          [(keyword (str (:name definition)))
                           {:rettype  (let [rt (:return-type definition)
                                            qt (qual-type @records-json rt)]
                                        (if (pointer-arg-type? rt)
                                          :pointer?
                                          (->rettype (type->keyword (or qt rt)))))

                            :argtypes (apply emit-fn-arg-types @records-json definition (reduce concat options))}]))))]
    (spit (doto (io/file cache)
            (io/make-parents))
          (->transit fns))
    fns))

(defn struct-name
  [t]
  (when t
    (let [[qualifier type-name] (str/split t #" ")]
      (when (= qualifier "struct")
        type-name))))

(defn ->struct-name
  [records-json t]
  (when t
    (or (struct-name t)
        (let [[qualifier _] (str/split t #" ")]
          (struct-name (qual-type records-json qualifier))))))

(defn get-struct-fields
  [records-json type-name]
  (set (->> (get-def* records-json :name type-name)
            :inner
            (filter :ownedTagDecl)
            first
            :ownedTagDecl
            :id
            (get-def-by-id records-json)
            :inner
            (map #(-> % :type :qualType))
            (map #(qual-type records-json %))
            (map #(->struct-name records-json %))
            (remove nil?))))

(defn emit-fn-structs
  [records-json fns]
  (for [f         @fns
        :let      [fn-name                        (if (string? f)
                                                    f
                                                    (first f))
                   {:keys [wrapped?] :as options} (if (string? f)
                                                    {:wrapped? true}
                                                    (second f))
                   definition                     (fn-type-def records-json fn-name)]
        type-name (map #(->struct-name records-json %)
                       (apply emit-fn-arg-type-names records-json definition (reduce concat options)))

        :when     type-name]
    type-name))

(defn structs->fields
  [records-json structs]
  (for [t structs]
    [t (get-struct-fields records-json t)]))

(defn recursive-struct-fields
  [records-json structs]
  (->> structs
       (iterate (fn [structs] (mapcat second (structs->fields records-json structs))))
       (take-while seq)
       (apply concat)
       set))

(defn with-header-prepostamble
  [{:keys [fns header-file] :or {header-file "wrapper.h"}} body]
  (let [hf (str "_"
                (str/upper-case (str/replace header-file #"[/.]" "_"))
                "_")]
    (str/join "\n"
              [(str-interp "#ifndef {{header-file}}" {:header-file hf})
               (str-interp "#define {{header-file}}" {:header-file hf})
               body
               "#endif"])))

(defn emit-wrapper-struct
  [records-json {:keys [return-type] :as fn-def} & {:keys [return]}]
  (when return
    (str-interp
     "typedef struct {{name}} {
  {{return-type}} value;
} {{name}};
"
     {:return-type return-type
      :name        (:name (:wrapper return))})))

(defn fn->name
  [fn-name]
  (if (vector? fn-name)
    (first fn-name)
    fn-name))

(defn emit-header
  [records-json & {:keys [fns header-file] :or {header-file "wrapper.h"} :as options}]
  (with-header-prepostamble options
    (-> (str/join "\n"
                  `["#include <stdint.h>\n"
                    "#include <gdnative_api_struct.gen.h>\n"
                    ""
                    "extern godot_gdnative_core_api_struct* api;"
                    "extern godot_gdnative_ext_nativescript_api_struct* nativescript_api;"
                    ""
                    ~@(let [fns (->> fns
                                     (group-by (comp :return-type (partial fn-type-def records-json) fn->name))
                                     vals
                                     (map first))]
                        (remove nil?
                                (for [f    fns
                                      :let [fn-name (fn->name f)]]
                                  (let [fn-def (fn-type-def records-json fn-name)]
                                    (some->
                                     (if (string? f)
                                       (emit-wrapper-struct records-json fn-def)
                                       (let [[fn-name options] f]
                                         (apply emit-wrapper-struct records-json fn-def (reduce concat options))))
                                     str)))))

                    ~@(for [f    fns
                            :let [fn-name (fn->name f)]]
                        (let [fn-def (fn-type-def records-json fn-name)]
                          (str
                           "\n"
                           (if (string? f)
                             (emit-wrapper-fn-signature records-json fn-def)
                             (let [[fn-name options] f]
                               (apply emit-wrapper-fn-signature records-json fn-def (reduce concat options))))
                           ";")))]
                  )
        (str "\n"))))

(defn emit-implementation
  [records-json & {:keys [fns header-file] :or {header-file "wrapper.h"}}]
  (->
   (str (str-interp "#include <{{header-file}}>\n\n" {:header-file header-file})
        "\n"
        (str/join "\n"
                  (for [fn-name fns]
                    (str
                     (if (string? fn-name)
                       (emit-wrapper-fn records-json (fn-type-def records-json fn-name))
                       (let [[fn-name options] fn-name]
                         (apply emit-wrapper-fn records-json (fn-type-def records-json fn-name) (reduce concat options))))
                     "\n"))))
   (str "\n")))

(defn layout
  [records-txt struct-name]
  (->> records-txt
       (filter #(re-find (re-pattern (str "^.*" struct-name ".*$")) (first %)))
       first
       (str/join "\n")))

(defn typedef->struct
  [records-json s]
  (->> (str/split s #"\n")
       (map (fn [line]
              (let [[_ _ name] (re-find #"(\|\W{1,3})(\w+)" line)]

                (or (when (and (seq name)
                               (not (#{"uint8_t"} name)))
                      (when-let [typedef (get-def records-json :name name :kind "TypedefDecl")]
                        (-> line
                            (str/replace #"(\|\W{1,3})(\w+)" (str "$1" (-> typedef :type :qualType)))
                            (str/replace #"\bbool\b" "uint8_t"))
                        ))
                    line))))
       (str/join "\n")))

(defn emit*
  [{:keys [cache records functions]}]
  (if (.exists (io/file cache))
    (<-transit (slurp cache))
    (let [result (emit-fns cache (delay (read-records-json records)) @functions)]
      (spit (doto (io/file cache)
              (io/make-parents))
            (->transit result))
      result)))

(defn emit
  [function-bindings]
  ;; TODO emit cache
  (apply merge (map emit* function-bindings)))

(defn emit-structs*
  [{:keys [records functions]}]
  (emit-fn-structs (read-records-json records) functions))

(defn sort-structs
  [structs]
  (-> (reduce (fn [g [type-name children]]
                (reduce (fn [g child]
                          (dep/depend g type-name child))
                        g
                        children))
              (dep/graph)
              structs)
      (dep/topo-sort)))

(defn emit-struct-names
  [function-bindings]
  (let [structs        (->> function-bindings
                            (mapcat (fn [{:keys [records functions]}]
                                      (let [records-json (read-records-json records)]
                                        (for [type-name (recursive-struct-fields records-json (emit-fn-structs records-json functions))]
                                          [type-name (get-struct-fields records-json type-name)]))))
                            set)
        sorted-structs (sort-structs structs)]

    (vec (distinct (reduce conj
                           (vec sorted-structs)
                           (mapv first structs))))))

(defn define-structs
  [structs]
  (let [{:keys [cache names records]} structs
        cache-json                    (when (.exists (io/file cache))
                                        (<-transit (slurp cache)))
        records-txt                   (delay (read-records (:txt records)))
        records-json                  (delay (read-records-json (:json records)))]

    (if cache-json
      (do (doseq [[k v] cache-json]
            (dtype-struct/define-datatype! k (:data-layout v)))
          (into {} cache-json))
      (let [result (for [struct-name @names]
                     (let [k (csk/->kebab-case-keyword struct-name :separator \_)]
                       [k (ffi-clang/defstruct-from-layout
                            k
                            (typedef->struct @records-json (layout @records-txt struct-name)))]))]
        (spit (doto (io/file cache)
                (io/make-parents))
              (->transit result))
        (into {} result)))))

(defn export-wrapper-fns
  [{:keys [functions records output]}]
  (let [records-json (read-records-json records)]
    (spit (:header output) (emit-header records-json :fns @functions))
    (spit (:implementation output) (emit-implementation records-json :fns @functions))))

(defn enums-map
  [{:keys [cache records types]}]
  (or (when (.exists (io/file cache))
        (<-transit (slurp cache)))
      (let [records-json (read-records-json (:json records))
            result       (into {}
                               (for [t types]
                                 [(csk/->kebab-case-keyword t)
                                  (into {} (mapv vector
                                                 (range)
                                                 (mapv #(csk/->kebab-case-keyword % :separator \_) (enum-values (:inner (get-def records-json :name t :kind "EnumDecl")))
                                                       )))]))]
        (spit (doto (io/file cache)
                (io/make-parents))
              (->transit result))
        result)))
