(ns test.scene-processing-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [godotclj.scene-processing :as scene-processing]
   [godotclj.tscn :as tscn]
   [godotclj.util :as util]))

(def dodge-the-creeps-file
  (io/file "src/clojure/test_resources/dodge_the_creeps"))

(def blitz001-file
  (io/file "src/clojure/test_resources/blitz001_godotclj"))

(deftest find-scene-files
  (is (= (map #(.getName %)
              (scene-processing/find-scene-files dodge-the-creeps-file))
         ["main.tscn"]))
  (is (= (->> blitz001-file
              scene-processing/find-scene-files
              (map #(.getName %))
              set)
         #{"BaseLevel.tscn" "Box.tscn" "FinishFlag.tscn" "Player.tscn"
           "StartMenu.tscn" "Level1.tscn" "Level2.tscn" "Level3.tscn"})))

(deftest id->resource-test
  (is (= (scene-processing/id->resource
          (->> (io/file blitz001-file "scenes/levels/Level1.tscn")
               slurp
               tscn/parse)
          1)
         {:resource "ext_resource",
          :attributes
          {:path "res://scenes/BaseLevel.tscn", :type "PackedScene", :id 1}}))
  (is (= (scene-processing/id->resource {} 2) nil)
      "Empty map/missing id"))

(deftest expand-res-path
  (is (= (.getPath (scene-processing/expand-res-path
                    dodge-the-creeps-file
                    "res://imaginary_dir/imaginary_scene.tscn"))
         (.getPath (io/file dodge-the-creeps-file
                            "imaginary_dir/imaginary_scene.tscn")))))

(deftest get-node-type
  (let [level1-scene
        (->> "scenes/levels/Level1.tscn"
             (io/file (.getPath blitz001-file))
             scene-processing/file->scene)]
    (is (= (scene-processing/get-node-type
            blitz001-file
            level1-scene
            (scene-processing/get-root-node level1-scene))
           "Node2D")
        "Instanced scene")))

(deftest project-file->class-map-test
  ;;; Note that the key is file name (without tscn), not the name of the base
  ;;; node
  ;;; TODO Test the case with duplicate file names
  ;;; (e.g. "res://Player/Main.tscn" and "res://Enemy/Main.tscn")
  (is (= (scene-processing/project-file->class-map dodge-the-creeps-file)
         {"main" {:base "Node"}}))
  (is (= (scene-processing/project-file->class-map blitz001-file)
         {"BaseLevel"  {:base "Node2D"}
          "Box"        {:base "StaticBody2D"}
          "FinishFlag" {:base "Area2D"}
          "Player"     {:base "KinematicBody2D"}
          "StartMenu"  {:base "Panel"}
          "Level1"     {:base "Node2D"}
          "Level2"     {:base "Node2D"}
          "Level3"     {:base "Node2D"}})))

(deftest merge-class-map-test
  ;; TODO Test deeper nesting
  (let [f (fn [a b] (+ a b))
        result (scene-processing/merge-class-map
                {"AlphaScene" {:base "Control"}
                 "BetaScene"  {:base "StaticBody2D"}}
                {"BetaScene"  {:methods {"sum" f}}})]
    (is (= (util/map-vals #(dissoc % :methods) result)
           {"AlphaScene" {:base "Control"}
            "BetaScene"  {:base "StaticBody2D"}}))
    (is (not= (get-in result ["BetaScene" :methods "sum"]) f)
        "Method should be wrapped")
    (is (not= (get-in result ["BetaScene" :methods "sum"]) util/simplify-method)
        "Method should not be `util/simplify-method` itself"))

  (is (= (scene-processing/merge-class-map
            {"AlphaScene" {:base "Control"}
             "BetaScene"  {:base "StaticBody2D"}}
            {"BetaScene"  {:base "Node2D"}})
           {"AlphaScene" {:base "Control"}
            "BetaScene"  {:base "Node2D"}})
        "Override `:base`"))
