(ns pathfinder.state
  (:require [reagent.core :as r]))

(defonce state
  (r/atom {:active-tab :profile   ;; :profile | :jobs | :courses

           ;; Structured Profile Map
           :profile {:name "Your Name"
                     :summary "Your profile summary"
                     :skills ["skill1" "skill2" "skill3" "skill4"]
                     :experience [{:company "Big Corp Ltd"
                                   :role "Senior Software Engineer"
                                   :years "2017 - 2024"
                                   :bullets ["Built high-performance financial data conversion pipelines."
                                             "Maintained backend systems and databases."]}
                                  {:company "WBS Coding School"
                                   :role "Data Science Student"
                                   :years "2026"
                                   :bullets ["Learned Python data analysis and machine learning workflows."
                                             "Constructed Python pipelines for analytics dashboards."]}]}

           :location "Frankfurt, Germany"
           :latitude 50.1106
           :longitude 8.6822
           :geo-radius 50

           ;; jobs pane
           :jobs []
           :loading? false
           :selected-job nil

           ;; :tailored-cv nil
           :tailored-cvs {}        ;; Map of {job-id "tailored text..."}
           :tailoring-job-id nil
           
           :recommended-courses []
           :courses-loading? false

           :city-suggestions []}))
