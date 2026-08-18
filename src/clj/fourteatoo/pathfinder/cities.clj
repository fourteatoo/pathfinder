(ns fourteatoo.pathfinder.cities
  (:require [fourteatoo.pathfinder.load :as load]
            [fourteatoo.pathfinder.jdbc :as jdbc]
            [tech.v3.dataset.modelling :as model]
            [tablecloth.api :as tc]
            [clojure.string :as s]
            [fourteatoo.pathfinder.config :as c]))


(def default-worldcities-path
  "../data/worldcities.csv")

(defn worldcities-path []
  (or (c/conf :datasets :cities)
      default-worldcities-path))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; 


(def default-countries
  ["Germany" "France" "Italy" "United Kingdom" "Spain"
   "Portugal" "Ireland" "Switzerland" "Austria" "Belgium"])

(defn drop-cities-table []
  (jdbc/drop-table "cities"))

(defn load-cities [file-path]
  (let [ds (load/load-dataset file-path)]
    (load/create-table-from-dataset ds "cities")
    (jdbc/insert-dataset ds "cities")))

(defn fetch-cities
  [& {:keys [countries min-population]
      :or {min-population 0}}]
  (if countries
    (jdbc/execute "select * from cities where country in ? and population > ?"
                  countries min-population)
    (jdbc/execute "select * from cities where population > ?"
                  min-population)))

(defn naive-random-locations [& {:keys [min-population]
                                 :or {min-population 500000}}]
  (let [locations (fetch-cities :countries default-countries
                                :min-population min-population)]
    (repeatedly #(rand-nth locations))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- build-cdf-index [cities]
  (let [cdf (rest (reductions (fn [[acc _] city]
                                [(+ acc (:population city)) city])
                              [0 nil]
                              cities))
        [total-weight _] (last cdf)]
    {:cdf (vec cdf)
     :total-weight (double total-weight)}))

;; 3. Binary search to sample in O(log N) time
(defn- search-cdf [cdf target]
  (loop [low 0
         high (dec (count cdf))]
    (if (>= low high)
      (second (nth cdf low))
      (let [mid (quot (+ low high) 2)
            [cum-weight _] (nth cdf mid)]
        (if (< target cum-weight)
          (recur low mid)
          (recur (inc mid) high))))))

(defn- sample-one [{:keys [cdf total-weight]}]
  (search-cdf cdf (rand total-weight)))

(defn random-locations [& {:keys [min-population]
                                  :or {min-population 500000}}]
  (let [index (build-cdf-index (fetch-cities :countries default-countries
                                             :min-population min-population))]
    (repeatedly #(sample-one index))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(comment
  (jdbc/describe-table "cities"))

(defn country-centroids-from-simplemaps
  "Builds a country -> [lat lon] map using the most populous city per
  country from SimpleMaps."
  [simplemaps-ds]
  (-> simplemaps-ds
      (tc/order-by :population :desc)
      ;; Retains the top (most populous) row per country directly
      (tc/unique-by :country)
      (tc/rows :as-maps)
      (->> (reduce (fn [acc {:keys [country lat lng]}]
                     (assoc acc country [lat lng]))
                   {}))))

(comment
  (country-centroids-from-simplemaps
   (tc/dataset (fetch-cities))))

(def country-centroids
  (delay (country-centroids-from-simplemaps
          (tc/dataset (fetch-cities)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- normalize-country-name
  "Strips common formal prefixes/suffixes, punctuation, and lowercases."
  [s]
  (when s
    (-> s
        (s/lower-case)
        ;; Remove bracketed notes like "(S.A.R.)" or "(the)"
        (s/replace #"\([^)]*\)" "")
        ;; Strip common formal descriptors
        (s/replace #"\b(republic|democratic|federation|islamic|plurinational|state of|kingdom of|of)\b" "")
        ;; Remove special characters/commas
        (s/replace #"[^a-z0-9\s]" "")
        (s/trim)
        ;; Collapse multiple spaces
        (s/replace #"\s+" " "))))

(defn- string-similarity
  "Simple token overlap score between two normalized strings."
  [s1 s2]
  (let [tokens1 (set (s/split (normalize-country-name s1) #"\s+"))
        tokens2 (set (s/split (normalize-country-name s2) #"\s+"))
        intersection (clojure.set/intersection tokens1 tokens2)]
    (if (or (empty? tokens1) (empty? tokens2))
      0.0
      (/ (count intersection) (max (count tokens1) (count tokens2))))))

(defn find-best-country-match
  "Finds the best SimpleMaps country match for a given country string."
  [country-name]
  (let [norm-so (normalize-country-name country-name)]
    (or
     ;; 1. Direct match
     (and (get @country-centroids country-name)
          country-name)
     
     ;; 2. Exact match on normalized string
     (some (fn [sm-country]
             (when (= norm-so (normalize-country-name sm-country))
               sm-country))
           (keys @country-centroids))
     
     ;; 3. High token-overlap or substring match (> 0.5 overlap)
     (let [matches (->> (keys @country-centroids)
                        (map (fn [sm] {:sm-country sm
                                      :score (string-similarity country-name sm)}))
                        (filter #(> (:score %) 0.5))
                        (sort-by :score >))]
       (:sm-country (first matches)))
     
     ;; 4. Explicit fallback for the rare complete mismatches (e.g., Holland -> Netherlands)
     (get {"Holland" "Netherlands"
           "United Kingdom of Great Britain and Northern Ireland" "United Kingdom"
           "Russian Federation" "Russia"
           "Czech Republic" "Chzechia"
           "Viet Nam" "Vietnam"
           "Myanmar" "Burma"
           "Venezuela, Bolivarian Republic of..." "Venezuela"
           "Syrian Arab Republic" "Syria"
           "Republic of Korea" "Korea, South"
           "Great Britain" "United Kingdom"} country-name))))
 
(comment
  @country-centroids)

(defn find-city-by-coords [latitude longitude]
  (jdbc/execute-one "SELECT * FROM cities
      ORDER BY ST_Distance_Sphere(ST_Point(lng, lat), ST_Point(?, ?)) ASC
      LIMIT 1;" longitude latitude))

(comment
  (find-city-by-coords 50.1106 8.6822))

(defn find-country-from-coords [latitude longitude]
  (:country (find-city-by-coords latitude longitude)))

(comment
 (find-country-from-coords 50.1106 8.6822))
