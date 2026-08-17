(ns fourteatoo.pathfinder.trends
  (:require [tablecloth.api :as tc]
            [clojure.string :as s]
            [fourteatoo.pathfinder.load :as load]
            [fourteatoo.pathfinder.jdbc :as jdbc]
            [scicloj.kindly.v4.kind :as kind]
            [clojure.set :as set]
            [fourteatoo.pathfinder.cities :as cities]
            [camel-snake-kebab.core :as csk]))


(def tech-survey-path "../data/stackoverflow/survey2025.csv.xz")

(defn- unroll-tech-col
  "Selects country + target tech column, splits delimited strings, and unrolls."
  [ds col-key]
  (if (tc/has-column? ds col-key)
    (-> ds
        (tc/select-columns [:country col-key])
        (tc/drop-missing [:country col-key])
        (tc/update-columns {col-key (fn [col] (map #(if (string? %) (s/split % #";") []) col))})
        (tc/unroll col-key)
        (tc/rename-columns {col-key :technology}))
    ;; Fallback empty dataset if the column doesn't exist in older survey years
    (tc/dataset {:country [] :technology []})))

(defn extract-all-tech-counts
  "Extracts counts for a set of survey column keys across all tech categories."
  [ds col-keys]
  (let [unrolled-datasets (map #(unroll-tech-col ds %) col-keys)
        combined-ds       (apply tc/concat unrolled-datasets)]
    (-> combined-ds
        (tc/group-by [:country :technology])
        (tc/aggregate {:count tc/row-count}))))

(defn analyze-year-file
  "Streams all technology columns (languages, platforms, databases,
  frameworks, tools) and builds an aggregated summary table grouped by
  country and technology."
  [csv-path year]
  (let [have-cols ["LanguageHaveWorkedWith" "DatabaseHaveWorkedWith" 
                   "PlatformHaveWorkedWith" "WebframeHaveWorkedWith" 
                   "MiscTechHaveWorkedWith" "ToolsTechHaveWorkedWith"]
        want-cols ["LanguageWantToWorkWith" "DatabaseWantToWorkWith" 
                   "PlatformWantToWorkWith" "WebframeWantToWorkWith" 
                   "MiscTechWantToWorkWith" "ToolsTechWantToWorkWith"]
        whitelist (concat ["Country" "Employment" "ConvertedCompYearly"] 
                          have-cols 
                          want-cols)
        raw-ds (load/load-dataset csv-path 
                                  {:column-whitelist whitelist
                                   :key-fn csk/->kebab-case-keyword})
        prof-ds (tc/select-rows raw-ds 
                                (fn [row] 
                                  (let [emp (get row :employment)]
                                    (and (string? emp) 
                                         (s/includes? emp "Employed")))))
        have-kebab-keys (map csk/->kebab-case-keyword have-cols)
        want-kebab-keys (map csk/->kebab-case-keyword want-cols)
        salary-unrolled (apply tc/concat
                               (for [col-key have-kebab-keys
                                     :when (tc/has-column? prof-ds col-key)]
                                 (-> prof-ds
                                     (tc/select-columns [:country col-key :converted-comp-yearly])
                                     (tc/drop-missing [:country col-key :converted-comp-yearly])
                                     (tc/update-columns {col-key (fn [col]
                                                                   (map #(if (string? %)
                                                                           (s/split % #";")
                                                                           [])
                                                                        col))})
                                     (tc/unroll col-key)
                                     (tc/rename-columns {col-key :technology}))))
        salary-ds (-> salary-unrolled
                      (tc/group-by [:country :technology])
                      (tc/aggregate {:median-salary
                                     #(tech.v3.datatype.functional/median (get % :converted-comp-yearly))}))
        have-counts (extract-all-tech-counts prof-ds have-kebab-keys)
        want-counts (extract-all-tech-counts prof-ds want-kebab-keys)
        prof-counts (-> prof-ds
                        (tc/group-by [:country])
                        (tc/aggregate {:prof-user-count tc/row-count}))
        joined-ds (-> have-counts
                      (tc/inner-join want-counts [:country :technology])
                      (tc/rename-columns {:count :have-count
                                          :right.count :want-count})
                      (tc/inner-join prof-counts [:country]) ;; Fixed typo: prof-counts
                      (tc/left-join salary-ds [:country :technology])
                      (tc/add-columns {:year year}))]
    (-> joined-ds
        (tc/map-columns :desirability [:want-count :have-count]
                        (fn [want have]
                          (if (and have (pos? have))
                            (double (/ want have))
                            0.0)))
        (tc/map-columns :adoption [:have-count :prof-user-count]
                        (fn [have profs]
                          (if (and profs (pos? profs))
                            (double (/ have profs))
                            0.0)))
        (tc/select-columns [:year :country :technology :have-count :want-count
                            :prof-user-count :desirability :adoption :median-salary]))))

;; NOTE: The years before 2025 have a different schema, so we don't
;; actually use them at the moment.
(defn generate-three-year-trend
  "Processes each year independently and concatenates them into a timeline table."
  [path-2023 path-2024 path-2025]
  (let [ds-23 (analyze-year-file path-2023 2023)
        ds-24 (analyze-year-file path-2024 2024)
        ds-25 (analyze-year-file path-2025 2025)]
    (-> (tc/concat ds-23 ds-24 ds-25)
        (tc/order-by [:technology :year]))))


(comment
  (def survey-csv "../data/stackoverflow/survey2025.csv.xz")
  (analyze-year-file survey-csv 2025)
  (analyze-year-file "../data/stackoverflow/survey2023.csv.xz" 2023)
  (generate-three-year-trend
   "../data/stackoverflow/survey2023.csv.xz"
   "../data/stackoverflow/survey2024.csv.xz"
   "../data/stackoverflow/survey2025.csv.xz"))


(defn drop-trends-table []
  (jdbc/drop-table "trends"))

(defn load-trends [file-path year]
  (let [ds (analyze-year-file file-path year)]
    (load/create-table-from-dataset ds "trends")
    (count (jdbc/insert-dataset ds "trends"))))

(comment
  (jdbc/drop-table "trends")
  (load-trends tech-survey-path 2025)
  (jdbc/execute "select * from trends"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- fetch-trends []
  (jdbc/execute "select * from trends"))

(defn fetch-trends-ds []
  (tc/dataset (fetch-trends)))

(def trends-list
  (delay
    (fetch-trends)))

(def trends-ds
  (delay
    (fetch-trends-ds)))

(defn- make-plotly-spec [chart-rows]
  {:data [{:x (map :have-count chart-rows)
           :y (map :desirability chart-rows)
           :text (map :technology chart-rows)
           :mode "markers+text"
           :textposition "top center"
           :type "scatter"
           :marker {:size 12
                    :color (map :median-salary chart-rows) ; Colors the dots by salary depth
                    :colorscale "Viridis"
                    :showscale true
                    :colorbar {:title "Median Salary ($)"}}}]
   :layout {:title "2025 Developer Market Dynamics"
            :xaxis {:title "Market Footprint (Have Worked With)"}
            :yaxis {:title "Desirability Ratio (Want / Have)"}
            :width 800
            :height 500
            :hovermode "closest"}})

(defn plot-trends [ds]
  (-> ds
      (tc/rows :as-maps)
      make-plotly-spec
      kind/plotly))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- tokenize-tech
  "Splits on whitespace/commas while preserving +, #, ., -, and / inside terms."
  [s]
  (->> (s/split (s/lower-case s) #"[^\w\+\#\.\-\/]+")
       (remove s/blank?)
       (into #{})))

(defn skills-match?
  "Returns true if the set of tokens in technology A is a subset of B, or B of A."
  [skill-a skill-b]
  (let [set-a (tokenize-tech skill-a)
        set-b (tokenize-tech skill-b)]
    (and (seq set-a)
         (seq set-b)
         (or (set/subset? set-a set-b)
             (set/subset? set-b set-a)))))

(comment
  (jdbc/execute "select * from trends limit 3"))

(defn find-so-metric-for-skill [skill country]
  (->> (jdbc/execute "select * from trends where country = ?" country)
       (some (fn [metric]
               (when (skills-match? skill (:technology metric))
                 metric)))))

(comment
  (find-so-metric-for-skill "Python Programming" "Germany")
  (find-so-metric-for-skill "Hadoop" "Germany")
  (find-so-metric-for-skill "Spark" "Germany")
  (map :technology (jdbc/execute "select * from trends where country = ?" "Germany")))

(defn- compute-weighted-salary
  "Calculates a headcount-weighted average median salary across matched skill metrics."
  [skills-data]
  (let [valid-pairs (keep (fn [metric]
                            (let [sal  (:median-salary metric)
                                  have (:have-count metric)]
                              (when (and sal have (pos? have))
                                [sal have])))
                          skills-data)]
    (when (seq valid-pairs)
      (let [total-weighted-sal (reduce + (map (fn [[sal have]] (* sal have)) valid-pairs))
            total-headcount    (reduce + (map second valid-pairs))]
        (when (pos? total-headcount)
          (double (/ total-weighted-sal total-headcount)))))))

(defn- course-market-metrics [course country]
  (let [skills (if (string? (:gained-skills course))
                 (clojure.string/split (:gained-skills course) #",")
                 (:gained-skills course))
        matched-metrics (->> skills
                             (map clojure.string/trim)
                             (map (fn [skill]
                                    (if-let [metric (find-so-metric-for-skill skill country)]
                                      ;; we fill both :technology (from the
                                      ;; DB) and the :skill for the UI.
                                      ;; They may be different.
                                      (assoc metric :skill skill))))
                             (remove nil?))
        avg-salary (when (seq matched-metrics)
                     (compute-weighted-salary matched-metrics))
        avg-adoption (when (seq matched-metrics) 
                       (/ (reduce + (map :adoption matched-metrics))
                          (count matched-metrics)))
        avg-desirability (when (seq matched-metrics)
                           (/ (reduce + (map :desirability matched-metrics))
                              (count matched-metrics)))]
    {:market-avg-salary avg-salary
     :market-avg-adoption avg-adoption
     :market-avg-desirability avg-desirability
     :skill-metrics matched-metrics}))

(comment
  (def c (jdbc/execute-one  "select * from courses where title = ? limit 1"
                            "NoSQL, Big Data, and Spark Foundations Specialization"))
  (course-market-metrics c "Germany"))

(defn enrich-course-with-market-data [course country]
  (merge course
         (course-market-metrics course country)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn extract-country-overview
  "Aggregates surveyed counts and median compensation per country."
  [csv-path]
  (let [raw-ds (load/load-dataset csv-path 
                                  {:column-whitelist ["Country" "ConvertedCompYearly"]
                                   :key-fn csk/->kebab-case-keyword})]
    (-> raw-ds
        (tc/drop-missing [:country])
        (tc/group-by [:country])
        (tc/aggregate {:surveyed-count tc/row-count
                       :median-salary #(let [salaries (remove nil? (get % :converted-comp-yearly))]
                                         (if (seq salaries)
                                           (tech.v3.datatype.functional/median salaries)
                                           0.0))})
        ;; Keep countries with a reasonable sample size to reduce map noise
        (tc/select-rows  #(>= (:surveyed-count %) 10))
        (tc/order-by :surveyed-count :desc))))

(comment
  (extract-country-overview "../data/stackoverflow/survey2025.csv.xz"))

(defn- dynamic-map-spec [geo-rows]
  (let [max-surveyed (apply max (map :surveyed-count geo-rows))
        base-size 6
        max-added-size 24]
    {:data [{:type "scattergeo"
             :lon (map :longitude geo-rows)
             :lat (map :latitude geo-rows)
             :text (map #(str (:country %) "\n (" (:surveyed-count %)
                              " surveyed, \nmedian salary = "
                              (:median-salary %)")") geo-rows)
             :mode "markers"
             :marker {:size (map (fn [r]
                                   (+ base-size 
                                      (* max-added-size 
                                         (Math/sqrt (/ (double (:surveyed-count r 0)) 
                                                       max-surveyed)))))
                                 geo-rows)
                      :color (map :median-salary geo-rows) ; Colors the dots by salary depth
                      :colorscale "Viridis"
                      :colorbar {:title "Median Salary (USD)"}
                      :showscale true
                      :line {:width 1 :color "white"}}}]
     :layout {:title "Global Distribution and Average Salary of Surveyed"
              :width 900
              :height 550
              :geo {:projection {:type "natural earth"}
                    :showland true
                    :landcolor "rgb(243, 243, 243)"
                    :countrycolor "rgb(210, 210, 210)"
                    :showcountries true}}}))

(defn plot-survey-geo-distribution [ds]
  (-> ds
      (tc/rows :as-maps)
      dynamic-map-spec
      kind/plotly))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn build-so->coords-lookup
  "Maps all Stack Overflow countries directly to [lat lng] using automated matching."
  [so-countries-seq simplemaps-centroids]
  (reduce (fn [acc so-country]
            (if-let [matched-sm-country (cities/find-best-country-match so-country simplemaps-centroids)]
              (assoc acc so-country (get simplemaps-centroids matched-sm-country))
              (do
                (println "WARNING: Could not match country:" so-country)
                acc)))
          {}
          (distinct so-countries-seq)))

(defn- all-countries-in-so-ds [ds]
  (-> ds
      (tc/drop-missing :country)
      (tc/unique-by :country)
      (get :country)
      seq))

(def so->sm-country
  (delay
    (build-so->coords-lookup
     (all-countries-in-so-ds
      (load/load-dataset "../data/stackoverflow/survey2025.csv.xz"
                         {:column-whitelist ["Country"]
                          :key-fn csk/->kebab-case-keyword}))
     (cities/country-centroids-from-simplemaps
      (tc/dataset (cities/fetch-cities))))))

(defn join-so-data-with-centroids
  "Attaches :latitude and :longitude coordinates to an aggregated Stack
  Overflow dataset."
  [ds]
  (let [ds (tc/drop-missing ds :country)
        ds (-> ds
               (tc/add-column :country
                              (map cities/find-best-country-match
                                   (get ds :country)))
               (tc/drop-missing :country))]
    (tc/add-columns ds {:latitude (map (fn [c]
                                         (first (get @cities/country-centroids c)))
                                       (get ds :country))
                        :longitude (map (fn [c]
                                          (second (get @cities/country-centroids c)))
                                        (get ds :country))})))

(comment
  (plot-survey-geo-distribution
   (join-so-data-with-centroids
    (extract-country-overview "../data/stackoverflow/survey2025.csv.xz"))))
