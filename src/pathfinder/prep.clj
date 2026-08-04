(ns pathfinder.prep
  (:require
   [camel-snake-kebab.core :as csk]
   [cheshire.core :as json]
   [clojure.data.csv :as csv]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [pathfinder.jdbc :as jdbc]
   [pathfinder.ml :as ml]
   [tablecloth.api :as tc]
   [clojure.string :as s]
   [tech.v3.dataset.sql :as ds-sql])
  (:import
   (org.tukaani.xz XZInputStream)
   (java.security MessageDigest)))

;; ## import the job descriptions and create the DB

#_(def job-descriptions-path "../data/job_descriptions.csv.xz")
(def linkedin-jobs-path "../data/linkedin/postings.csv.xz")
#_(def courses-path "../data/coursera_course_2024.csv")
(def courses-path "../data/Coursera.csv")
(def sample-profiles-path "../data/profiles.edn")
;; (def skills-path "../data/skills_en.csv")


(defn- xz-reader [file-path]
  (-> (io/file file-path)
      (io/input-stream)
      (XZInputStream.)
      (io/reader)))

(defn- xz-file? [name]
  (s/ends-with? (str name) ".xz"))

(defn- csv-reader [file-name]
  (if (xz-file? file-name)
    (xz-reader file-name)
    (io/reader file-name)))

(defn load-xz-csv-dataset [file-path & {:keys [num-rows]}]
  (let [opts (cond-> {:key-fn csk/->kebab-case-keyword
                      :file-type :csv}
               num-rows (assoc :num-rows num-rows))]
    (with-open [in (xz-reader file-path)]
      (tc/dataset in opts))))

(defn- load-dataset [path & {:keys [num-rows]}]
  (if (xz-file? path)
    (load-xz-csv-dataset path :num-rows num-rows)
    (tc/dataset path {:num-rows num-rows})))

(defn load-edn [file-path]
  (with-open [in (io/input-stream file-path)]
    (-> (repeatedly #(edn/read in))
        (take-while some?))))



;; ### generate embeddings DB

(defn make-job-embeddings [ds]
  (mapv (fn [title skills desc]
          (let [text (str title " " skills " " desc)]
            (ml/make-embedding text)))
        (:job-title ds)
        (:skills ds)
        (:job-description ds)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; 

;; Map Tablecloth/tech.ml.dataset datatypes to DuckDB SQL types
(defn- tc-type->duckdb-type [dt]
  (case dt
    (:int8 :int16 :int32 :uint8 :uint16) "INT"
    (:int64 :uint32 :uint64)             "BIGINT"
    (:float32 :float)                    "FLOAT"
    (:float64 :double)                   "DOUBLE"
    :boolean                             "BOOLEAN"
    :packed-local-date                   "DATE"
    (:string :text :symbol)              "VARCHAR"
    :zoned-date-time "DATETIME"
    ;; that's the only type of vector we handle in this program
    :persistent-vector "FLOAT[]"))

(defn- csv-header->keyword [x]
  (-> (name x)
      s/trim
      s/lower-case
      (s/replace #"[^\w\s_-]" "")
      (s/replace #"[\s_]" "-")
      keyword))

(defn- dataset-sql-columns [ds]
  (let [info (tc/info ds)]
    (map (fn [name type]
           {:name (csv-header->keyword name)
            :type (tc-type->duckdb-type type)})
         (:col-name info)
         (:datatype info))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- csv-columns [path]
  ;; 20 rows should be enough to get an impression of what kind of
  ;; data is in the columns and infer their type.
  (->> (load-dataset path :num-rows 10)
       dataset-sql-columns))

(defn create-table-from-dataset
  ([ds]
   (create-table-from-dataset ds (tc/dataset-name ds)))
  ([ds table-name]
   (->> (dataset-sql-columns ds)
        (jdbc/create-table table-name))))

(defn create-table-from-csv
  "Create the DB table `table-name` with the column types inferred by
  Tablecloth."
  [table-name path]
  (jdbc/create-table table-name (csv-columns path)))

(comment
  (csv-columns courses-path))

(defn read-csv [reader]
  (let [data (csv/read-csv reader)
        headers (map-indexed (fn [i c]
                               (-> (if (empty? c)
                                     (str "column-" i)
                                     c)
                                   csv-header->keyword))
                             (first data))]
    (map (fn [row]
           (zipmap headers row))
         (rest data))))

(comment
  (with-open [reader (io/reader (io/file "../data/skills_en.csv"))]
    (-> (csv/read-csv reader)
        first)))


(defn load-table-from-csv [table-name path & {:keys [batch-size]
                                              :or {batch-size 5000}}]
  (create-table-from-csv table-name path)
  (with-open [reader (csv-reader path)]
    (jdbc/load-table table-name
                     (read-csv reader)
                     :batch-size batch-size)))

(comment
  (def ds (-> (load-dataset job-descriptions-path :num-rows 10)
              ;; add an empty column
              (tc/add-column :embedding [] {:datatype [:float32]})))
  (-> (tc/add-column ds :embedding [[]])
      (tc/info)))

(defn format-job-description-for-embedding [row]
  (str "Title: " (:job_title row)
       " | Role: " (:role row)
       " | Skills: " (:skills row)
       " | Description: " (:job_description row)))

(defn format-course-for-embedding [row]
  (str "Course: " (:title row)
       " | Skills Taught: " (:gained_skills row)
       " | Subject: " (:subject row)))

(defn format-skill-for-embedding [row]
  (str "Course: " (:title row)
       " | Skills Taught: " (:gained_skills row)
       " | Subject: " (:subject row)))

(defn- create-job-embeddings-table []
  (jdbc/create-table "job_embeddings"
                     [{:name :job-id
                       :type "INT64"
                       :key :primary}
                      {:name :embedding
                       :type "FLOAT[]"}]))

(defn- create-course-embeddings-table []
  (jdbc/create-table "course_embeddings"
                     [{:name :course-id
                       :type "INT64"
                       :key :primary}
                      {:name :embedding
                       :type "FLOAT[]"}]))

#_(defn- load-job-descriptions []
  (load-table-from-csv "job_descriptions" job-descriptions-path))

(defn- load-linkedin-jobs []
  (load-table-from-csv "linkedin_jobs" linkedin-jobs-path))

(comment
  (def data-jobs (tc/dataset "../data/data_jobs.csv" {:key-fn csk/->kebab-case-keyword}))
  (def cols (:col-name (tc/info data-jobs)))
  ;; [:job-title-short, :job-title, :job-location, :job-via, :job-schedule-type, :job-work-from-home, :search-location, :job-posted-date, :job-no-degree-mention, :job-health-insurance, :job-country, :salary-rate, :salary-year-avg, :salary-hour-avg, :company-name, :job-skills, :job-type-skills]
  (filter #(s/includes? % "Python") (:job-title data-jobs))
  (tc/select-columns (tc/info data-jobs) [:col-name :datatype :n-missing :n-valid])
  (let [dates (sort  (:job-posted-date data-jobs))]
    [(first dates)
     (last dates)])
  (create-table-from-dataset data-jobs "data_jobs")
  (time (load-linkedin-jobs)))

(defn- load-cities []
  (let [ds (tc/dataset "../data/worldcities.csv")]
    (create-table-from-dataset ds "cities")
    (jdbc/insert-dataset ds "cities")))


(def default-countries
  ["Germany", "France", "Italy", "United Kingdom", "Spain",
   "Portugal", "Switzerland", "Austria", "Belgium"])

(defn fetch-cities
  [& {:keys [countries min-population]
      :or {min-population 100000}}]
  (if countries
    (jdbc/execute "select * from cities where country in ? and population > ?"
                  countries min-population)
    (jdbc/execute "select * from cities where population > ?"
                  min-population)))

(defn- random-locations [& {:keys [min-population]
                            :or {min-population 500000}}]
  (let [locations (fetch-cities :countries default-countries
                                :min-population min-population)]
    (repeatedly #(rand-nth locations))))

(defn assign-random-cities-to-jobs
  [& {:keys [min-population batch-size]
      :or {min-population 500000
           batch-size 5000}}]
  (let [cities (fetch-cities :countries default-countries
                             :min-population min-population)]
    (->> (jdbc/execute "SELECT job_id from job_descriptions")
         (map (fn [city j]
                [(:city city)
                 (:country city)
                 (:lng city)
                 (:lat city)
                 (:job_id j)])
              (random-locations :min-population min-population))
         (partition-all batch-size)
         (run! (fn [batch]
                 (jdbc/execute-batch
                  "UPDATE job_descriptions SET location = ?, country = ?, longitude = ?, latitude = ? where job_id = ?"
                  batch))))))

(comment
  (assign-random-cities-to-jobs)
  (jdbc/describe-table "cities")
  (jdbc/describe-table "job_descriptions")
  (count (assign-random-cities-to-jobs :min-population 500000)))

(comment
  (jdbc/execute "select count(*) from cities")
  (jdbc/execute "select count(*) from cities where country = 'Holland'")
  (def european-cities (count (jdbc/execute "select * from cities where country in ('Germany', 'France', 'Italy', 'United Kingdom', 'Spain', 'Portugal', 'Poland', 'Switzerland', 'Austria', 'Belgium') and population > 100000")))
  (jdbc/describe-table "cities")
  (keys (first (jdbc/execute "select * from job_descriptions limit 10")))
  ;; (:role :job_posting_date :company_size :job_title :experience :contact_person :longitude :responsibilities :qualifications :preference :skills :benefits :salary_range :latitude :job_description :company_profile :location :contact :work_type :country :company :job_id :job_portal)
  (def cities (tc/dataset "../data/worldcities.csv" {:key-fn csk/->kebab-case-keyword}))
  (tc/select-columns (tc/info cities) [:col-name :datatype])
  (def jobs (tc/select-columns (load-jobs-with-embeddings 100) [:job-id :location :country]))
  )

;; [:job-title-short, :job-title, :job-location, :job-via, :job-schedule-type, :job-work-from-home, :search-location, :job-posted-date, :job-no-degree-mention, :job-health-insurance, :job-country, :salary-rate, :salary-year-avg, :salary-hour-avg, :company-name, :job-skills, :job-type-skills]

(comment
  (tc/column-names (tc/dataset "../data/data_jobs.csv")))

(defn- sha256 [s]
  (.digest (MessageDigest/getInstance "SHA-256")
           (.getBytes (str s) "UTF-8")))

(defn- generate-id [& parts]
  (let [raw-str (s/lower-case (s/join " || " parts))
        bytes (sha256 raw-str)]
    (Math/abs (.getLong (java.nio.ByteBuffer/wrap bytes)))))

(comment
  (def jobs (tc/dataset (tc/dataset "../data/postings.csv")))
  (tc/column-names jobs)
  #_("job_title" "company" "job_location" "job_link" "first_seen" "search_city" "search_country" "job level" "job_type" "job_summary" "job_skills")
  )

(defn- format-job-posting-for-embedding [row]
  (str "Title: " (row "job_title")
       " | Type: " (row "job_type")
       " | Skills: " (row "skills")
       " | Description: " (row "description")))

(defn- load-job-postings
  [& {:keys [min-population]
      :or {min-population 500000}}]
  (let [ds (-> (tc/dataset "../data/postings.csv")
               (tc/map-columns "job_id" ["job_title" "company" "job_location" "job_link"]
                               generate-id)
               (tc/unique-by ["job_id"]))
        locations (random-locations :min-population min-population)
        ds (-> ds
               (tc/add-columns {"latitude" (map :lat locations)
                                "longitude" (map :lng locations)
                                "location" (map :city locations)
                                "country" (map :country locations)})
               (tc/rename-columns {"job_summary" "description"
                                   "job_skills" "skills"
                                   "job level" "job_level"})
               (tc/add-column :embedding (map #(ml/make-embedding
                                                  (format-job-posting-for-embedding %))
                                                (tc/rows ds :as-maps)))
               (tc/drop-columns ["search_city" "search_country" "first_seen"]))]
    (println (tc/select-columns (tc/info ds) [:col-name :datatype]))
    (create-table-from-dataset ds "jobs")
    (count (jdbc/insert-dataset ds "jobs"))))

(comment
  (jdbc/drop-table "jobs")
  (load-job-postings)
  (def jobs (tc/dataset "../data/postings.csv"))
  (tc/select-columns (tc/info jobs) [:col-name :datatype])
  (let [ds (tc/dataset "../data/postings.csv")]
    (create-table-from-dataset ds "jobs")
    (jdbc/insert-dataset ds "jobs"))
  (jdbc/describe-table "jobs"))

;; REMOVE ME -wcp03/08/26
#_(defn- load-data-jobs [& {:keys [min-population]
                            :or {min-population 500000}}]
    (let [cities (fetch-cities :countries default-countries
                               :min-population min-population)
          ds (-> (tc/dataset "../data/data_jobs.csv")
                 (tc/map-columns :job-id ["job_title" "company_name" "job_location"]
                                 generate-id)
                 (tc/unique-by [:job-id]))
          locations (repeatedly (tc/row-count ds) #(rand-nth cities))
          ds (-> (tc/add-columns ds {"latitude" (map :lat locations)
                                     "longitude" (map :lng locations)
                                     "location" (map :city locations)
                                     "country" (map :country locations)})
                 (tc/map-columns :embedding ["job_title" "description"]
                                 (ml/make-embedding
                                  (format-job-description-for-embedding row)))
                 (tc/rename-columns {"job_title_short" "title_short"
                                     "job_schedule_type" "schedule_type"
                                     "job_work_from_home" "work_from_home"
                                     "job_posted_date" "posted_date"
                                     "job_skills" "skills"})
                 (tc/drop-columns ["job_location" "job_country" "job_no_degree_mention"
                                   "job_health_insurance"]))]
      (create-table-from-dataset ds "jobs")
      (count (jdbc/insert-dataset ds "jobs"))))

(comment
  (jdbc/drop-table "jobs")
  (time (load-data-jobs))
  (jdbc/describe-table "jobs"))

(defn assign-random-cities-to-jobs
  [& {:keys [min-population batch-size]
      :or {min-population 500000
           batch-size 5000}}]
  (let [cities (fetch-cities :countries default-countries
                             :min-population min-population)]
    (->> (jdbc/execute "SELECT job_id from jobs")
         (map (fn [j]
                (let [city (rand-nth cities)]
                  [(:city city)
                   (:country city)
                   (:lng city)
                   (:lat city)
                   (:job_id j)])))
         (partition-all batch-size)
         (run! (fn [batch]
                 (jdbc/execute-batch
                  "UPDATE job_descriptions SET location = ?, country = ?, longitude = ?, latitude = ? where job_id = ?"
                  batch))))))

(comment
  (load-data-jobs))

(defn- load-courses []
  ;; the courses set is rather small, we can afford to load it all at
  ;; once.
  (let [ds (-> (tc/dataset courses-path)
               (tc/map-columns :course-id ["Title" "Institution"]
                               generate-id)
               (tc/unique-by [:course-id]))]
    
    (create-table-from-dataset ds "courses")
    (count (jdbc/insert-dataset ds "courses"))))

(comment
  (def courses (tc/dataset courses-path))
  (tc/select-columns  (tc/info courses) [:col-name :datatype])
  (tc/info courses)
  (load-courses)
  (drop-courses-table)
  (jdbc/list-tables)
  (jdbc/describe-table "courses"))

#_(defn- load-courses []
    (create-table-from-csv "courses" courses-path)
    (with-open [reader (csv-reader courses-path)]
      (jdbc/load-table "courses" (read-csv reader)))
    (jdbc/rename-column "courses" "column_0" "course_id"))

(comment
  (jdbc/list-tables)
  (jdbc/describe-table "courses")
  (jdbc/execute "SELECT * from courses"))

#_(defn- drop-job-descriptions-table []
  (jdbc/drop-table "job_descriptions"))

#_(defn- drop-job-embeddings-table []
  (jdbc/drop-table "job_embeddings"))

(defn- drop-courses-table []
  (jdbc/drop-table "courses"))

(defn- drop-course-embeddings-table []
  (jdbc/drop-table "course_embeddings"))

(defn refresh-job-embeddings
  "Check which job doesn't have an embedding, yet, and add it."
  []
  (reduce (fn [n row]
            (jdbc/insert-job-embedding
             (:job_id row)
             (ml/make-embedding
              (format-job-description-for-embedding row)))
            (if (zero? (mod n 100))
              (println n))
            (inc n))
          0
          (jdbc/fetch-unembedded-job-descriptions)))

(defn refresh-course-embeddings
  "Check which course doesn't have an embedding, yet, and add it."
  [& [max-rows]]
  (reduce (fn [n row]
            (jdbc/insert-course-embedding
             (:course_id row)
             (ml/make-embedding
                   (format-course-for-embedding row)))
            (if (zero? (mod n 100))
              (println n))
            (inc n))
          0
          (jdbc/fetch-unembedded-courses max-rows)))

(defn bootstrap-db []
  ;; drop the tables if still around
  (jdbc/drop-table "jobs")
  (drop-courses-table)
  (drop-course-embeddings-table)
  ;; reload the tables
  (load-courses)
  (create-course-embeddings-table)
  (refresh-course-embeddings)
  (load-job-postings))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
  (let [job-vec (ml/make-embedding (cond (map? job)
                                         (format-job-description-for-embedding job)
                                         (string? job)
                                         job))
        cv-vec  (ml/make-embedding cv-text)
        sql "WITH scored_courses AS (
                 SELECT *,
                     array_distance(e.embedding::FLOAT[384], ?::FLOAT[384]) AS job_dist,
                     array_distance(e.embedding::FLOAT[384], ?::FLOAT[384]) AS cv_dist
                 FROM courses c
                 JOIN course_embeddings e ON c.course_id = e.course_id
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
  (let [query-vec (ml/make-embedding query-text)
        geo-radius (when (and latitude longitude)
                     geo-radius)
        sql-statement (str "SELECT *, array_distance(e.embedding::FLOAT[384], ?::FLOAT[384]) AS match_dist"
                           (when (and latitude longitude)
                             ", (ST_Distance_Sphere(ST_Point(j.longitude, j.latitude), ST_Point(?, ?)) / 1000.0) AS geo_dist")
                           " FROM job_descriptions j JOIN job_embeddings e ON j.job_id = e.job_id
                             WHERE match_dist < ?"
                           (when (and latitude longitude)
                             " and geo_dist <= ?")
                           (when country
                             " and country = ?")
                           " ORDER BY match_dist ASC LIMIT ?")]
    (prn sql-statement)                 ; -wcp01/08/26
    (prn (remove nil? [query-vec longitude latitude match-distance geo-radius country limit])) ; -wcp01/08/26
    (->> (apply jdbc/execute sql-statement
                (remove nil? [query-vec longitude latitude match-distance geo-radius country limit]))
         (map #(dissoc % :embedding))
         (map #(update % :company_profile json/parse-string)))))

(comment
  (jdbc/execute "select count(*) from job_descriptions where country = 'Germany'")
  (jdbc/execute "select * from job_descriptions where location = 'Frankfurt' limit 20")
  (def cv "python backend engineer with some java experience")
  (def jobs (search-jobs cv :match-distance 1))
  (jdbc/execute "select job_posting_date, count(job_posting_date) from job_descriptions group by job_posting_date order by job_posting_date")
  (jdbc/execute "select * from cities where city = 'Frankfurt'")
  (map :location
       (search-jobs "python developer" :latitude 50.1106, :longitude 8.6822 :geo-radius 200 :limit 20))
  (search-jobs "python developer" :limit 20)
  (let [jobs (search-jobs cv :match-distance 1 :country "USA")
        first-match (first jobs)]
    (->> (search-jobs cv :match-distance 1
                      :latitude (:latitude first-match)
                      :longitude (:longitude first-match)
                      :geo-radius 400)
         (map #(select-keys % [:job_id :country :latitude :longitude]))))
  (search-jobs cv :match-distance 1 :latitude 50.1106 :longitude 8.6822 :geo-radius 50 :limit 20)
  (first jobs)
  (->> (search-jobs cv :match-distance 1 :limit 100)
       (map #(select-keys % [:country :latitude :longitude])))
  (jdbc/execute "select count(*) from job_descriptions where country = 'USA'")
  (recommend-courses-for-job (first jobs) cv
                             :cv-gap-threshold 0.8 :max-job-dist 1.1))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn search-courses [query-text & {:keys [limit] 
                                    :or {limit 10}}]
  (let [query-vec (ml/make-embedding query-text)]
    (jdbc/execute
     "SELECT c.course_id, c.title, c.subject, c.institution,
       array_distance(e.embedding::FLOAT[384], ?::FLOAT[384]) AS distance
FROM courses c
JOIN course_embeddings e ON c.course_id = e.course_id
ORDER BY distance ASC
LIMIT ?"
     query-vec #_country limit)))

