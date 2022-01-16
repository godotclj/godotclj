(ns build
  (:require [clojure.tools.build.api :as b])
  (:require [godotclj.api.gen-gdscript :refer [gen-api]]))

(defn clean [_]
  (b/delete {:path "target"}))

(defn prep
  "Prepare library for use from another deps project"
  [_]
  (gen-api "target/classes"))
