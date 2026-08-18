(ns pathfinder.jobs
  (:require [pathfinder.state :refer [state]]
            [pathfinder.util :as util :refer [encode-transit decode-transit]]))


(defn tailor-cv! [job]
  (let [job-id (:job-id job)]
    (swap! state assoc :tailoring-job-id job-id)
    (-> (js/fetch "/api/tailor-cv"
                  (clj->js
                   {:method "POST"
                    ;; :headers {"Content-Type" "application/json"}
                    :headers {"Content-Type" "application/transit+json"
                              "Accept"       "application/transit+json"}
                    :body (encode-transit
                           {:cv (:profile @state)
                            :job job-id})}))
        (.then (fn [res]
                 (if (.-ok res)
                   (.text res) #_(.json res)
                   (throw (js/Error. (str "Tailoring failed: " (.-status res)))))))
        (.then (fn [data]
                 (let [result (decode-transit data) #_(js->clj data :keywordize-keys true)
                       tailored-text (or (:tailored-cv result) (:cv result) (pr-str result))]
                   (swap! state assoc-in [:tailored-cvs job-id] tailored-text)
                   (swap! state assoc :tailoring-job-id nil))))
        (.catch (fn [err]
                  (js/console.error "Tailor error:" err)
                  (js/alert (str "Could not tailor CV: " (.-message err)))
                  (swap! state assoc :tailoring-job-id nil))))))

(defn fetch-course-recommendations! [job]
  (swap! state assoc :courses-loading? true :selected-job job)
  (-> (js/fetch "/api/recommend-courses"
                (clj->js
                 {:method "POST"
                  ;; :headers {"Content-Type" "application/json"}
                  :headers {"Content-Type" "application/transit+json"
                            "Accept"       "application/transit+json"}
                  :body (encode-transit {:job (:job-id job)
                                         :profile (:profile @state)
                                         ;; these are for the trend stats
                                         :latitude (:latitude @state)
                                         :longitude (:longitude @state)})}))
      (.then (fn [res]
               (if (.-ok res)
                 (.text res) #_(.json res)
                 (throw (js/Error. (str "Course recommendation error: " (.-status res)))))))
      (.then (fn [data]
               (let [courses (decode-transit data) #_(js->clj data :keywordize-keys true)]
                 (swap! state assoc 
                        :recommended-courses courses 
                        :courses-loading? false 
                        :active-tab :courses))))
      (.catch (fn [err]
                (js/console.error "Course fetch error:" err)
                (swap! state assoc :courses-loading? false)))))

(defn distance->score [dist]
  (if (number? dist)
    (let [score (js/Math.round (* (- 1.0 dist) 100))]
      (str (js/Math.max 0 score) "%"))
    "N/A"))

(defn parse-skills-string [skills-str]
  (when (seq skills-str)
    (->> (clojure.string/split skills-str #",")
         (map clojure.string/trim)
         (remove clojure.string/blank?))))

(defn job-card [job]
  (let [job-id (:job-id job)
        tailored-text (get-in @state [:tailored-cvs job-id])
        is-tailoring? (= (:tailoring-job-id @state) job-id)
        geo-dist (:geo-dist job)
        dist-km (when geo-dist (js/Math.round geo-dist))
        skills-list (parse-skills-string (:skills job))]
    [:div {:class "card bg-base-100 shadow-md border border-base-200 p-6 mb-4 space-y-4"}
     
     ;; Header & Badges
     [:div {:class "flex justify-between items-start gap-4"}
      [:div {:class "space-y-1"}
       [:h3 {:class "text-xl font-bold"} (:job-title job)]
       [:p {:class "text-sm text-base-content/70"} 
        (:company job) " • " (:location job)
        (when (:country job) (str ", " (:country job)))
        (when dist-km (str " (" dist-km " km away)"))]
       
       ;; Metadata Badges (Level, Type)
       [:div {:class "flex flex-wrap gap-2 pt-1"}
        (when-let [level (:job-level job)]
          [:span {:class "badge badge-info badge-sm"} level])
        (when-let [job-type (:job-type job)]
          [:span {:class "badge badge-secondary badge-outline badge-sm"} job-type])]]
      
      [:div {:class "flex flex-col items-end gap-2"}
       [:div {:class "badge badge-accent"} 
        (str "Match: " (distance->score (:match-dist job)))]
       
       [util/render-link "Original offer" (:job-link job)]]]

     ;; Skill Tags Section
     (when (seq skills-list)
       [util/skill-tag-cloud skills-list]
       #_[:div {:class "space-y-1"}
        [:p {:class "text-xs font-semibold text-base-content/60"} "Required Skills:"]
        [:div {:class "flex flex-wrap gap-1.5"}
         (for [skill skills-list]
           ^{:key skill}
           [:span {:class "badge badge-ghost badge-outline badge-sm"} skill])]])

     ;; Description
     [:div {:class "text-sm whitespace-pre-line text-base-content/80 max-h-48 overflow-y-auto pr-2 border-l-2 border-base-300 pl-3"}
      (:description job)]

     ;; Action Buttons
     [:div {:class "flex gap-2 justify-end pt-2 border-t border-base-200"}
      [:button {:class "btn btn-outline btn-sm"
                :on-click #(fetch-course-recommendations! job)}
       "Find Courses"]

      [:button {:class "btn btn-primary btn-sm"
                :on-click #(tailor-cv! job)
                :disabled is-tailoring?}
       (if is-tailoring?
         [:span {:class "loading loading-spinner loading-xs"} "Tailoring..."]
         "Tailor CV for this Job")]]

     ;; Tailored CV Output View
     (when tailored-text
       [:div {:class "mt-4 p-4 bg-base-200 rounded-lg border border-primary/30"}
        [:div {:class "flex justify-between items-center mb-2"}
         [:h4 {:class "font-bold text-primary"} "Tailored Resume Version"]
         [:button {:class "btn btn-xs btn-ghost"
                   :on-click #(js/navigator.clipboard.writeText tailored-text)}
          "Copy to Clipboard"]]
        [:pre {:class "whitespace-pre-wrap font-mono text-xs p-3 bg-base-100 rounded border border-base-300 max-h-96 overflow-y-auto"}
         tailored-text]])]))

(defn jobs-tab []
  (let [jobs (:jobs @state)]
    [:div {:class "max-w-4xl mx-auto p-6 space-y-4"}
     [:h2 {:class "text-2xl font-bold mb-4"} "🔍  Job Search Results"]
     (if (seq jobs)
       (for [j jobs]
         ^{:key (or (:job-id j) (:job-title j))} [job-card j])
       [:div
        [:div {:class "alert alert-info"}
         [:span "No job search results yet. Return to the Profile tab to search."]]
        [:img {:src "images/desktop.jpg"}]])]))
