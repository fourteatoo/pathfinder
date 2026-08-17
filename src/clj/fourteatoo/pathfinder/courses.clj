(ns fourteatoo.pathfinder.courses
  (:require [fourteatoo.pathfinder.load :as load]
            [tablecloth.api :as tc]
            [fourteatoo.pathfinder.search :as search]
            [fourteatoo.pathfinder.jdbc :as jdbc]
            [fourteatoo.pathfinder.util :as util]
            [clojure.string :as s]
            [clojure.set :as set]))


(def courses-path "../data/Coursera.csv")
(def courses2-path
  "../data/Coursera_Data.csv")

(defn format-course-for-embedding [row]
  (str "Course: " (:title row)
       ;; " || Subject: " (:subject row)
       " || Skills Taught: " (:gained-skills row)
       " || Level: " (:level row)
       " || Description:" (:description row)))

(defn load-courses [file-path]
  ;; the courses set is rather small, we can afford to load it all at
  ;; once.
  (let [ds (-> (load/load-dataset file-path)
               (tc/map-columns :course-id [:title :institution]
                               util/generate-id)
               (tc/unique-by [:course-id]))
        ds (-> ds
               (tc/add-column :embedding
                              (map #(search/make-embedding
                                     (format-course-for-embedding %))
                                   (tc/rows ds :as-maps))))]
    (load/create-table-from-dataset ds "courses")
    (count (jdbc/insert-dataset ds "courses"))))

(defn drop-courses-table []
  (println "dropping courses table")
  (jdbc/drop-table "courses"))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


;; With additional data, missing from the base CSV, above

(defn- parse-python-list-regex
  [s]
  (if (s/blank? s)
    []
    (->> (re-seq #"'([^']*)'" s)
         (mapv second))))

(defn- parse-rating [s]
  (when (not (s/blank? s))
    (-> (s/trim s)
        parse-double)))

(defn- parse-reviews [s]
  (when (not (s/blank? s))
    (-> (s/trim s)
        (s/replace #" +reviews$" "")
        (s/replace #"," "")
        parse-long)))

(defn load-courses2 [file-path]
  ;; the courses set is rather small, we can afford to load it all at
  ;; once.
  (let [ds (-> (load/load-dataset file-path)
               (tc/drop-columns [:column-15 :column-16])
               (tc/rename-columns {:course-title :title
                                   ;; ... :subject
                                   :what-you-will-learn :description
                                   :course-type :learning-product
                                   :course-url :url})
               (tc/map-columns :gained-skills [:skill-gain]
                               #(s/join ", " (parse-python-list-regex %)))
               (tc/map-columns :instructors [:instructor]
                               #(s/join ", " (parse-python-list-regex %)))
               (tc/map-columns :institution [:offered-by]
                               #(s/join ", " (parse-python-list-regex %)))
               (tc/map-columns :rate [:rating] parse-rating)
               (tc/map-columns :reviews [:review] parse-reviews)
               (tc/drop-columns [:rating :instructor :review :skill-gain :syllabus :modules :keyword])
               (tc/map-columns :course-id [:title :institution]
                               util/generate-id)
               (tc/unique-by [:course-id]))
        ds (-> ds
               (tc/add-column :embedding
                              (map #(search/make-embedding
                                     (format-course-for-embedding %))
                                   (tc/rows ds :as-maps))))]
    (load/create-table-from-dataset ds "courses")
    (count (jdbc/insert-dataset ds "courses"))))

(comment
  (jdbc/describe-table "courses")
  (jdbc/execute "select * from courses limit 3"))
