(ns fourteatoo.pathfinder.prep
  (:require
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]
   [clojure.string :as s]
   [fourteatoo.pathfinder.jdbc :as jdbc]
   [fourteatoo.pathfinder.load :as load]
   [fourteatoo.pathfinder.search :as search]
   [tablecloth.api :as tc]
   [fourteatoo.pathfinder.trends :as trends]
   [fourteatoo.pathfinder.util :as util]
   [fourteatoo.pathfinder.jobs :as jobs]
   [fourteatoo.pathfinder.courses :as courses]
   [fourteatoo.pathfinder.cities :as cities]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; 

(defn bootstrap-db []
  (println "Dropping tables")
  ;; drop the tables if still around
  (cities/drop-cities-table)
  (jobs/drop-jobs-table)
  (courses/drop-courses-table)
  (trends/drop-trends-table)
  ;; reload the tables
  (println "Loading tables")
  (println "loading cities")
  (cities/load-cities cities/worldcities-path)
  (println "loading jobs")
  (jobs/load-job-postings jobs/job-postings-path)
  (println "loading courses")
  ;; (courses/load-courses courses/courses-path)
  (courses/load-courses2 courses/courses2-path)
  (println "loading trends")
  (trends/load-trends trends/tech-survey-path 2025))

(comment
  (bootstrap-db))

