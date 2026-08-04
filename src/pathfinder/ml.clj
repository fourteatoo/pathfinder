(ns pathfinder.ml
  (:require
   [embeddings.core :as embed]))

(defonce embedding-model
  (embed/load-model "../models/all-MiniLM-L6-v2"))

(defn make-embedding [text]
  (vec (embed/embed embedding-model text)))

(comment
  (type (make-embedding "I ate an Apple; it was crunchy"))
  (type (embed/embed embedding-model "I ate an Apple; it was crunchy")))


