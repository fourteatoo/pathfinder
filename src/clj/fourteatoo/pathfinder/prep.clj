(ns fourteatoo.pathfinder.prep
  (:require
   [camel-snake-kebab.core :as csk]
   [clojure.data.csv :as csv]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as s]
   [fourteatoo.pathfinder.jdbc :as jdbc]
   [fourteatoo.pathfinder.search :as search]
   [tablecloth.api :as tc])
  (:import
   (java.security MessageDigest)
   (org.tukaani.xz XZInputStream)))

(def courses-path "../data/Coursera.csv")
(def job-postings-path "../data/postings.csv")
(def sample-profiles-path "../data/profiles.edn")
(def worldcities-path "../data/worldcities.csv")


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

(defn load-dataset [file-path & {:keys [num-rows]}]
  (let [opts (cond-> {:key-fn csk/->kebab-case-keyword
                      :file-type :csv}
               num-rows (assoc :num-rows num-rows))]
    (with-open [in (csv-reader file-path)]
      (tc/dataset in opts))))

#_(defn- load-dataset [path & {:keys [num-rows]}]
  (if (xz-file? path)
    (load-xz-csv-dataset path :num-rows num-rows)
    (tc/dataset path {:num-rows num-rows})))

(defn load-edn [file-path]
  (with-open [in (io/input-stream file-path)]
    (-> (repeatedly #(edn/read in))
        (take-while some?))))



#_(defn make-job-embeddings [ds]
  (mapv (fn [title skills desc]
          (let [text (str title " " skills " " desc)]
            (search/make-embedding text)))
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

(defn format-course-for-embedding [row]
  (str "Course: " (:title row)
       " || Subject: " (:subject row)
       " || Skills Taught: " (:gained-skills row)
       " || Level: " (:level row)))

(comment
  (def data-jobs (load-dataset "../data/data_jobs.csv"))
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
  (let [ds (load-dataset worldcities-path)]
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

(defn- sha256 [s]
  (.digest (MessageDigest/getInstance "SHA-256")
           (.getBytes (str s) "UTF-8")))

(defn- generate-id [& parts]
  (let [raw-str (s/lower-case (s/join " || " parts))
        bytes (sha256 raw-str)]
    (Math/abs (.getLong (java.nio.ByteBuffer/wrap bytes)))))

(comment
  (def jobs (load-dataset job-postings-path))
  (tc/column-names jobs)
  #_("job_title" "company" "job_location" "job_link" "first_seen" "search_city" "search_country" "job level" "job_type" "job_summary" "job_skills")
  )

(defn- load-job-postings
  [& {:keys [min-population]
      :or {min-population 500000}}]
  (let [ds (-> (load-dataset job-postings-path)
               (tc/rename-columns {:job-summary :description
                                   :job-skills :skills
                                   #_#_:job-level "job_level"})
               (tc/map-columns :job-id [:job-title :company :job-location :job-link]
                               generate-id)
               (tc/unique-by [:job-id]))
        locations (random-locations :min-population min-population)
        ds (-> ds
               (tc/add-columns {:latitude (map :lat locations)
                                :longitude (map :lng locations)
                                :location (map :city locations)
                                :country (map :country locations)})
               (tc/add-column :embedding (map #(search/make-embedding
                                                (search/format-job-posting-for-embedding %))
                                              (tc/rows ds :as-maps)))
               (tc/drop-columns [:search-city :search-country :job-location :first-seen]))]
    (create-table-from-dataset ds "jobs")
    (count (jdbc/insert-dataset ds "jobs"))))

(comment
  (jdbc/drop-table "jobs")
  (load-job-postings)
  (def jobs (load-dataset job-postings-path))
  (tc/select-columns (tc/info jobs) [:col-name :datatype])
  (let [ds (load-dataset job-postings-path)]
    (create-table-from-dataset ds "jobs")
    (jdbc/insert-dataset ds "jobs"))
  (jdbc/describe-table "jobs"))

(defn- load-courses []
  ;; the courses set is rather small, we can afford to load it all at
  ;; once.
  (let [ds (-> (load-dataset courses-path)
               (tc/map-columns :course-id [:title :institution]
                               generate-id)
               (tc/unique-by [:course-id]))
        ds (-> ds
               (tc/add-column :embedding
                              (map #(search/make-embedding
                                     (format-course-for-embedding %))
                                   (tc/rows ds :as-maps))))]
    (create-table-from-dataset ds "courses")
    (count (jdbc/insert-dataset ds "courses"))))

(defn- drop-jobs-table []
  (println "dropping jobs table")
  (jdbc/drop-table "jobs"))

(defn- drop-courses-table []
  (println "dropping courses table")
  (jdbc/drop-table "courses"))

(defn bootstrap-db []
  (println "Dropping tables")
  ;; drop the tables if still around
  (drop-jobs-table)
  (drop-courses-table)
  ;; reload the tables
  (println "Loading tables")
  (println "loading jobs")
  (load-job-postings)
  (jdbc/describe-table "jobs")
  (println "loading courses")
  (load-courses))

(comment
  (bootstrap-db))
