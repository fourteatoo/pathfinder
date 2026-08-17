(ns fourteatoo.pathfinder.util
  (:require
   [fastmath.vector :as v]
   [clojure.string :as s])
  (:import
   (java.security MessageDigest)))


;; to calculate semantic similarity

(defn cosine-similarity [v1 v2]
  (let [dot (v/dot v1 v2)
        n1  (v/dist v1)
        n2  (v/dist v2)]
    (if (or (zero? n1) (zero? n2))
      0.0
      (/ dot (* n1 n2)))))

(defn sha256 [s]
  (.digest (MessageDigest/getInstance "SHA-256")
           (.getBytes (str s) "UTF-8")))

(defn generate-id [& parts]
  (let [raw-str (s/lower-case (s/join " || " parts))
        bytes (sha256 raw-str)]
    (Math/abs (.getLong (java.nio.ByteBuffer/wrap bytes)))))

