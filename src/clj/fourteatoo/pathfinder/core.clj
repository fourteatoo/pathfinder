(ns fourteatoo.pathfinder.core
  (:require
   [fourteatoo.pathfinder.api :as api]
   [fourteatoo.pathfinder.jdbc :as jdbc]
   [mount.core :as mount])
  (:gen-class))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (mount/start))

(comment
  (mount/stop))
