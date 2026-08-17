(ns fourteatoo.pathfinder.jobs
  (:require [fourteatoo.pathfinder.jdbc :as jdbc]
            [fourteatoo.pathfinder.load :as load]
            [tablecloth.api :as tc]
            [fourteatoo.pathfinder.util :as util]
            [fourteatoo.pathfinder.cities :as cities]
            [fourteatoo.pathfinder.search :as search]
            [scicloj.kindly.v4.kind :as kind]))

(def job-postings-path "../data/postings.csv")

(defn drop-jobs-table []
  (println "dropping jobs table")
  (jdbc/drop-table "jobs"))

(defn load-job-postings
  [file-path & {:keys [min-population]
                :or {min-population 500000}}]
  (let [ds (-> (load/load-dataset file-path)
               (tc/rename-columns {:job-summary :description
                                   :job-skills :skills
                                   #_#_:job-level "job_level"})
               (tc/map-columns :job-id [:job-title :company :job-location :job-link]
                               util/generate-id)
               (tc/unique-by [:job-id]))
        locations (cities/random-locations :min-population min-population)
        ds (-> ds
               (tc/add-columns {:latitude (map :lat locations)
                                :longitude (map :lng locations)
                                :location (map :city locations)
                                :country (map :country locations)})
               (tc/add-column :embedding (map #(search/make-embedding
                                                (search/format-job-posting-for-embedding %))
                                              (tc/rows ds :as-maps)))
               (tc/drop-columns [:search-city :search-country :job-location :first-seen]))]
    (load/create-table-from-dataset ds "jobs")
    (count (jdbc/insert-dataset ds "jobs"))))

(comment
  (jdbc/drop-table "jobs")
  (load-job-postings)
  (def jobs (load/load-dataset job-postings-path))
  (tc/select-columns (tc/info jobs) [:col-name :datatype])
  (let [ds (load/load-dataset job-postings-path)]
    (load/create-table-from-dataset ds "jobs")
    (jdbc/insert-dataset ds "jobs"))
  (jdbc/describe-table "jobs"))

(defn fetch-jobs []
  (tc/dataset (jdbc/execute "select * from jobs")))

(comment
  (jdbc/describe-table "jobs")
  (tc/dataset (jdbc/execute "select job_title from jobs")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- geo-rows [ds]
  (-> ds
      (tc/drop-missing [:latitude :longitude :location])
      ;; Group by coordinates and name to count identical entries
      (tc/group-by [:latitude :longitude :location])
      (tc/aggregate {:offer-count tc/row-count})
      (tc/rows :as-maps)))

;; Calculate a dynamic scaling factor based on the max volume to prevent huge markers

(defn- dynamic-map-spec [geo-rows]
  (let [max-offers (apply max (map :offer-count geo-rows))
        base-size 8]
    {:data [{:type "scattergeo"
             :lon (map :longitude geo-rows)
             :lat (map :latitude geo-rows)
             ;; Custom dynamic hover text showing the count
             :text (map #(str (:location-name %) " (" (:offer-count %) " jobs)") geo-rows)
             :mode "markers"
             :marker {:size (map (fn [r] 
                                   ;; Math utility scaling the size gracefully up to 30px max
                                   (+ base-size (* 25 (/ (:offer-count r) max-offers)))) 
                                 geo-rows)
                      :opacity 0.75
                      :color "crimson"
                      :line {:width 1 :color "white"}}}]
     :layout {:title "Global Distribution and Density of Job Offers"
              :width 900
              :height 550
              :geo {:projection {:type "natural earth"}
                    :showland true
                    :landcolor "rgb(243, 243, 243)"
                    :countrycolor "rgb(210, 210, 210)"
                    :showcountries true}}}))

(defn plot-job-offers [ds]
  (-> (geo-rows ds)
      dynamic-map-spec
      kind/plotly))
