;;; # Career Pathfinder Engine POC

;;; ## Project Overview
;;; The Career Pathfinder Engine is a functional, data-driven decision
;;; system built on the JVM in Clojure. Its goal is to bridge the gap
;;; between a job seeker's current profile, market realities (demand,
;;; salary lift, market saturation), and targeted educational
;;; opportunities.

;;; Rather than acting as a black-box machine learning model or a simple
;;; skill-matching index, the engine models candidate decision-making
;;; across four explicit dimensions:
;;; 1. Baseline Capability (Exploit / Current Skills)
;;; 2. Strategic Intent (Explore / Stated Interests)
;;; 3. Hard Constraints (Exclusions / Veto Keywords & Distance Radius)
;;; 4. Resource Capacity (Sabbatical Duration & Weekly Hour Budget)

;;; ## Core Architectural Philosophy
;;; - Functional Data Pipelines over Heavy Frameworks :: Built using
;;;   Scicloj libraries (~tablecloth~, ~tech.ml.dataset~, ~fastmath~).
;;; - Zero-Latency Local Execution :: Operates entirely in-memory or
;;;   against local DuckDB/SQLite storage without cloud API rate limits or
;;;   runtime token costs.
;;; - REPL-Driven Literate Presentation :: Developed and rendered directly
;;;   via Clay and Quarto, producing a self-contained HTML report.

(ns pathfinder.notebook
  (:require
   [aerial.hanami.templates :as ht]
   [clojure.string :as str]
   [embeddings.core :as embed]
   [fastmath.vector :as v]
   [pathfinder.jdbc :as db]
   [pathfinder.load :refer :all]
   [scicloj.clay.v2.api :as clay]
   [scicloj.kindly.v4.api :as kindly]
   [scicloj.kindly.v4.kind :as kind]
   [scicloj.metamorph.ml.rdatasets :as rdatasets]
   [scicloj.tableplot.v1.hanami :as hanami]
   [tablecloth.api :as tc]
   [tech.v3.dataset.print :as print]
   [tech.v3.datatype :as dt]
   [tech.v3.datatype.datetime :as datetime]
   [pathfinder.prep :as prep]))

;; Start Clay with live reload enabled:
(clay/start!)

;; if needed, Clay can be stopped
(comment (clay/stop!))


;; get just the top 100 to play around

(defonce job-descriptions
  (prep/load-jobs-with-embeddings 100))

(tc/info job-descriptions)

(defonce courses
  (load-xz-csv courses-path))

(tc/info courses)

(defonce profiles
  (load-edn sample-profiles-path))

;; to calculate semantic similarity

(defn cosine-similarity [v1 v2]
  (let [dot (v/dot v1 v2)
        n1  (v/dist v1)
        n2  (v/dist v2)]
    (if (or (zero? n1) (zero? n2))
      0.0
      (/ dot (* n1 n2)))))

;; Instant In-Memory Ranking Test
(defn sample-rankings [target-candidate sample-jobs-ds]
  (let [cand-vec (:embedding target-candidate)]
    (-> sample-jobs-ds
        (tc/add-column :similarity
                       (fn [ds] (map #(cosine-similarity cand-vec %) (:embedding ds))))
        (tc/order-by [[:similarity :desc]]))))


(tc/select-columns (tc/info job-descriptions) [:col-name :datatype :n-valid :n-missing])

;; ## With the database
;;
;; ...but we ha ve a database pre-populated with data courtesy of Kaggle
;; https://www.kaggle.com/datasets/ravindrasinghrana/job-description-dataset
;;


(defn process-candidate-pipeline
  "End-to-end pipeline: takes candidate EDN profile and returns full match trace EDN."
  [candidate-profile]
  (let [{:keys [candidate-id skills preferred-location min-salary exclude-keywords]} candidate-profile
        
        ;; 1. Construct search query from user skills/interests
        candidate-query-text (str "Skills: " (clojure.string/join ", " skills))
        
        ;; 2. Fetch top matching jobs (Step 3)
        top-jobs (search-jobs candidate-query-text :limit 5)
        
        ;; 3. Process each job match
        processed-matches
        (mapv
          (fn [job]
            (let [job-skills (:job_descriptions/skills job "")
                  
                  ;; Extract missing skills via vector comparison (Step 4)
                  skill-gaps (extract-skill-gaps-vector skills job-skills :threshold 0.72)
                  
                  ;; Map each missing skill to the top course recommendations (Step 5)
                  recommended-courses
                  (mapv (fn [{:keys [skill similarity]}]
                          (let [top-course (first (search-courses skill :limit 1))]
                            {:missing-skill skill
                             :recommended-course-id (:courses/course_id top-course)
                             :course-title (:courses/title top-course)
                             :institution (:courses/institution top-course)}))
                        skill-gaps)]
              
              {:job-id (:job_descriptions/job_id job)
               :job-title (:job_descriptions/job_title job)
               :company (:job_descriptions/company job)
               :distance (:distance job)
               :skill-gaps (mapv :skill skill-gaps)
               :recommended-courses recommended-courses
               :rationale (str "Matched based on candidate skills [" 
                               (clojure.string/join ", " skills) "]. "
                               (if (seq skill-gaps)
                                 (str "Identified " (count skill-gaps) " skill gaps.")
                                 "Full skill overlap detected!"))}))
          top-jobs)]
    
    ;; 4. Assemble final EDN trace (Step 6 & 7)
    {:candidate-id candidate-id
     :timestamp (System/currentTimeMillis)
     :matches-found (count processed-matches)
     :results processed-matches}))
