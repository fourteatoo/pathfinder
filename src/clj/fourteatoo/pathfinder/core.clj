(ns fourteatoo.pathfinder.core
  (:require
   [fourteatoo.pathfinder.api :as api]
   [fourteatoo.pathfinder.jdbc :as jdbc]
   [mount.core :as mount])
  (:gen-class))

(defn -main
  [& args]
  (mount/start))

(comment
  (mount/stop))
