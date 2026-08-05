(ns pathfinder.state
  (:require [reagent.core :as r]))

(defonce state
  (r/atom {:active-tab :profile   ;; :profile | :jobs | :courses
           :cv-text ""
           :cv-edn {:name "John Doe"
                    :location "Paris, Texas, USA"
                    :skills ["Python" "DuckDB" "Systems Programming" "FreeBSD"]
                    :experience [{:company "Tech Corp Ltd"
                                  :role "Senior Backend & Systems Engineer"
                                  :years "2018 - 2025"
                                  :bullets ["Built high-performance Clojure services"
                                            "Managed system logs and PostgreSQL/DuckDB pipelines"]}]}
           :jobs []
           :selected-job nil
           :tailored-cv nil
           :loading? false
           :recommended-courses []
           :courses-loading? false}))
