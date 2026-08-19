(ns fourteatoo.pathfinder.search
  (:require
   [fourteatoo.pathfinder.jdbc :as jdbc]
   [embeddings.core :as embed]
   [mount.core :as mount]
   [clojure.string :as s]))

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

(defn- format-experience-item [{:keys [role company years bullets]}]
  (let [header (s/trim (str role " at " company " (" years ")"))
        bullet-text (s/join " " (map s/trim bullets))]
    (if (seq bullet-text)
      (str "- " header ": " bullet-text)
      (str "- " header))))

(defn- format-profile-for-embedding [profile]
  (let [{:keys [summary skills experience]} profile
        skills-str (s/join ", " (map s/trim skills))
        exp-str (->> experience
                     (map format-experience-item)
                     (s/join "\n"))]
    (s/join "\n"
              (filter seq
                      [(when (seq summary) (str "Summary: " (s/trim summary)))
                       (when (seq skills-str) (str "Core Skills: " skills-str))
                       (when (seq exp-str) (str "\nWork Experience:\n" exp-str))]))))

(defn- course-level-matches-familiarity? [cv-dist course-level]
  (let [level (case course-level
                (nil "Mixed") "Intermediate"
                course-level)]
    (cond
      ;; Candidate knows this domain well -> Drop "Beginner" intro courses
      (and (< cv-dist 0.35) (= level "Beginner"))
      false
      ;; Candidate is totally new to this domain -> Drop "Advanced" courses
      (and (> cv-dist 0.65) (= level "Advanced"))
      false
      :else true)))

(defn filter-recommendations-by-cv-distance [recommended-courses]
  (remove (fn [course]
            (let [cv-dist (or (:cv-dist course) 0.5)
                  level (:level course)]
              (not (course-level-matches-familiarity? cv-dist level))))
          recommended-courses))

(defn recommend-courses-for-job
  "Recommends top courses for a job that fill the candidate's skill
  gaps.  The `cv` is supposed to be a map of the form:

    {:name \"Name Surname\"
     :summary \"profile summary\"
     :skills [\"skill1\" \"skill2\" \"skill3\" ...]
     :experience [{:company \"Big Corp Ltd\"
                   :role \"Senior Software Engineer\"
                   :years \"2017 - 2024\"
                   :bullets [\"Built high-performance financial data conversion pipelines.\"
                             \"Maintained backend systems and databases.\"]}
                  {:company \"WBS Coding School\"
                   :role \"Data Science Student\"
                   :years \"2026\"
                   :bullets [\"Learned Python data analysis and machine learning workflows.\"
                             \"Constructed Python pipelines for analytics dashboards.\"]}]}

   - min-cv-gap (default 0.45): Courses closer than this to the CV are dropped as 'already known'.
   - max-job-dist (default 0.85): Upper bound to keep courses relevant to the job domain."
  [job cv & {:keys [min-cv-gap max-job-dist limit]
             :or {min-cv-gap 0.45
                  max-job-dist 0.85
                  limit 10}}]
  (assert (and job cv))
  (let [job-vec (make-embedding (cond (map? job)
                                      (format-job-posting-for-embedding job)
                                      (string? job)
                                      job))
        cv-vec (-> (if (string? cv)
                     cv
                     (format-profile-for-embedding cv))
                   make-embedding)
        sql "WITH scored_courses AS (
                 SELECT *,
                     array_cosine_distance(embedding::FLOAT[384], ?::FLOAT[384]) AS job_dist,
                     array_cosine_distance(embedding::FLOAT[384], ?::FLOAT[384]) AS cv_dist
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
  [profile & {:keys [match-distance country limit geo-radius latitude longitude]
              :or {match-distance 0.6
                   limit 10
                   geo-radius 5}}]
  (let [query-text (if (string? profile)
                     profile
                     (format-profile-for-embedding profile))
        query-vec (make-embedding query-text)
        geo-radius (when (and latitude longitude)
                     geo-radius)
        sql-statement (str "SELECT *, array_cosine_distance(embedding::FLOAT[384], ?::FLOAT[384]) AS match_dist"
                           (when (and latitude longitude)
                             ", (ST_Distance_Sphere(ST_Point(longitude, latitude), ST_Point(?, ?)) / 1000.0) AS geo_dist")
                           " FROM jobs
                             WHERE match_dist < ?"
                           (when (and latitude longitude)
                             " and geo_dist <= ?")
                           (when country
                             " and country = ?")
                           " ORDER BY match_dist ASC LIMIT ?")
        params (concat [query-vec]
                       (when (and longitude latitude geo-radius)
                         [longitude latitude])
                       [match-distance]
                       (when (and longitude latitude geo-radius)
                         [geo-radius])
                       (when country
                         [country])
                       [limit])]
    (->> (apply jdbc/execute sql-statement params)
         (map #(dissoc % :embedding)))))

(defn fetch-job [job-id]
  (jdbc/execute-one "SELECT * from jobs WHERE job_id = ? LIMIT 1" job-id))

(comment
  (->> (search-jobs "java programmer" :latitude 50.1106 :longitude 8.6822 :geo-radius 1000 :limit 10 :match-distance 0.6)
       count))

(defn search-cities [query-str]
  (jdbc/execute
   "SELECT *
      FROM cities
      WHERE lower(city) LIKE lower(?)
      LIMIT 10"
   (str query-str "%")))

(defn find-nearest-city [lat lon]
  (jdbc/execute-one
   "SELECT *, ST_Distance_Spheroid(ST_Point(lng, lat), ST_Point(?, ?)) / 1000.0 AS distance_km
      FROM cities
      ORDER BY distance_km ASC
      LIMIT 1"
   lon lat))

(comment
  (find-nearest-city 42 42)
  (search-cities "Frankfurt"))

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
