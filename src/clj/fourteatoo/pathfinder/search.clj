(ns fourteatoo.pathfinder.search
  (:require
   [fourteatoo.pathfinder.jdbc :as jdbc]
   [embeddings.core :as embed]
   [mount.core :as mount]))

(defonce embedding-model
  (embed/load-model "../models/all-MiniLM-L6-v2"))

(defn make-embedding [text]
  (vec (embed/embed embedding-model text)))

(comment
  (type (make-embedding "I ate an Apple; it was crunchy"))
  (type (embed/embed embedding-model "I ate an Apple; it was crunchy")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn format-job-posting-for-embedding [row]
  (str "Title: " (:job-title row)
       ;; " || Type: " (:job-type row)
       " || Skills: " (:skills row)
       " || Description: " (:description row)))

(comment
  (jdbc/execute "select * from courses where title = 'Introduction to Artificial Intelligence (AI)'"))

(defn recommend-courses-for-job
  "Recommends top courses for a job that fill the candidate's skill gaps.
   - min-cv-gap (default 0.45): Courses closer than this to the CV are dropped as 'already known'.
   - max-job-dist (default 0.85): Upper bound to keep courses relevant to the job domain."
  [job cv-text & {:keys [min-cv-gap max-job-dist limit]
                  :or {min-cv-gap 0.45
                       max-job-dist 0.85
                       limit 10}}]
  (let [job-vec (make-embedding (cond (map? job)
                                      (format-job-posting-for-embedding job)
                                      (string? job)
                                      job))
        cv-vec  (make-embedding cv-text)
        sql "WITH scored_courses AS (
                 SELECT *,
                     array_distance(embedding::FLOAT[384], ?::FLOAT[384]) AS job_dist,
                     array_distance(embedding::FLOAT[384], ?::FLOAT[384]) AS cv_dist
                 FROM courses
             )
             SELECT *
             FROM scored_courses
             WHERE cv_dist > ?      -- Candidate doesn't know it yet
               AND job_dist < ?     -- Course is relevant to the job
             ORDER BY job_dist ASC  -- Best job alignment first
             LIMIT ?"]
    (->> (jdbc/execute sql job-vec cv-vec min-cv-gap max-job-dist limit)
         (map #(dissoc % :embedding)))))

(defn search-jobs
  [query-text & {:keys [match-distance country limit geo-radius latitude longitude]
                 :or {match-distance 1
                      limit 10
                      geo-radius 5}}]
  (let [query-vec (make-embedding query-text)
        geo-radius (when (and latitude longitude)
                     geo-radius)
        sql-statement (str "SELECT *, array_distance(embedding::FLOAT[384], ?::FLOAT[384]) AS match_dist"
                           (when (and latitude longitude)
                             ", (ST_Distance_Sphere(ST_Point(longitude, latitude), ST_Point(?, ?)) / 1000.0) AS geo_dist")
                           " FROM jobs
                             WHERE match_dist < ?"
                           (when (and latitude longitude)
                             " and geo_dist <= ?")
                           (when country
                             " and country = ?")
                           " ORDER BY match_dist ASC LIMIT ?")]
    (->> (apply jdbc/execute sql-statement
                (remove nil? [query-vec longitude latitude match-distance geo-radius country limit]))
         (map #(dissoc % :embedding)))))

(comment
  (mount/start)
  (def cv "python backend engineer with some java experience")
  (def jobs (search-jobs cv :match-distance 1))
  
  (search-jobs "python developer" :limit 20)
  (search-jobs cv :match-distance 1 :latitude 50.1106 :longitude 8.6822 :geo-radius 50 :limit 20)
  (first jobs)
  (jdbc/describe-table "courses")
  (recommend-courses-for-job (first jobs) cv
                             :min-cv-gap 0.8 :max-job-dist 1.1))
