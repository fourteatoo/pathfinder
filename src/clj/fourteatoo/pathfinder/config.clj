(ns fourteatoo.pathfinder.config
  (:require [cprop.core :refer [load-config]]
            [mount.core :as mount]
            [clojure.java.io :as io]))


(defn- home-conf []
  (io/file (System/getProperty "user.home") ".pathfinder"))

(defn- load-configuration
  [& [file]]
  (let [file (or file (home-conf))]
    (if (and file (.exists (io/file file)))
      (load-config :file file)
      (load-config))))

(mount/defstate config
  :start (load-configuration))

(defn conf [& path]
  (get-in config path))
