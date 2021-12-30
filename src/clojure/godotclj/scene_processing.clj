(ns godotclj.scene-processing
  "Place to get information from `.tscn` files.

  Conventions:
  - `file` :: File object
  - `scene` :: Map that represents `.tscn` file (obtained with `tscn/parse`)
  - `project-root` :: File object that is the root of the project (`res://`)
  - `node` :: Similar to `scene` but represents a node resource"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [godotclj.tscn :as tscn]
   [godotclj.util :as util]))

(defn find-scene-files
  [file]
  (->> file
       file-seq
       (filter #(re-matches #".*\.tscn" (.getName %)))))

(defn root-node?
  [m]
  (and (= (:resource m) "node") (not (contains? (:attributes m) :parent))))

(defn get-root-node
  [scene]
  (->> scene
       (filter root-node?)
       first))

(defn file->scene
  [file]
  (-> file slurp tscn/parse))

(defn id->resource [scene id]
  (->> scene
       (filter #(and (= (:resource %) "ext_resource")
                     (= (get-in % [:attributes :id]) id)))
       first))

(defn expand-res-path
  [project-root path]
  (io/file project-root (str/replace path "res://" "")))

(defn get-node-type
  [project-root scene node]
  (if (contains? (:attributes node) :instance)
    (->> (get-in node [:attributes :instance :arguments])
         first
         (id->resource scene)
         (#(get-in % [:attributes :path]))
         (expand-res-path project-root)
         file->scene
         get-root-node
         (get-node-type project-root scene))
    (get-in node [:attributes :type])))

(defn gen-class-map
  "Given a node map, return map that is suitable for registration."
  [project-root scene node class-name]
  {class-name {:base (get-node-type project-root scene node)}})

(defn project-file->class-map
  [project-root]
  (->> project-root
       find-scene-files
       (map (fn [f]
              {:name (->> f .getName (re-find #"(.*)\.tscn") second)
               :scene (file->scene f)
               :node (get-root-node (file->scene f))}))
       (map (fn [m] (gen-class-map project-root (:scene m) (:node m) (:name m))))
       (apply merge)))

(defn merge-class-map
  [class-map user-def]
  (->> (merge-with util/class-map-merger class-map user-def)
       (util/map-vals (fn mapper [val]
                        (cond
                          (map? val) (util/map-vals mapper val)
                          (fn? val) (util/simplify-method val)
                          :else val)))))
