(ns pathfinder.courses
  (:require [reagent.core :as r]))

;; --- API CALL HELPER ---
(defn fetch-course-recommendations! [state job-id]
  (swap! state assoc :courses-loading? true)
  (-> (js/fetch "/api/recommend-courses"
                #js {:method "POST"
                     :headers #js {"Content-Type" "application/json"}
                     :body (js/JSON.stringify #js {:cv_text (:cv-text @state)
                                                   :job_id job-id})})
      (.then #(.json %))
      (.then (fn [json-data]
               (let [courses (js->clj json-data :keywordize-keys true)]
                 (swap! state assoc 
                        :recommended-courses courses 
                        :courses-loading? false))))
      (.catch (fn [err]
                (js/console.error "Course retrieval failed" err)
                (swap! state assoc :courses-loading? false)))))

;; --- HELPERS FOR METRICS ---
(defn dist->pct
  "Converts a cosine/vector distance (0.0 to 1.0) into a percentage match (0% to 100%)."
  [dist]
  (if (nil? dist)
    0
    (int (Math/max 0 (Math/min 100 (* (- 1.0 dist) 100))))))

;; --- TAB 3 COMPONENT ---
(defn courses-tab [state]
  (let [{:keys [jobs selected-job recommended-courses courses-loading?]} @state]
    [:div {:class "max-w-6xl mx-auto p-6 space-y-6"}
     
     ;; Header & Job Selector Banner
     [:div {:class "card bg-base-100 shadow-xl p-6"}
      [:div {:class "flex flex-col md:flex-row md:items-center justify-between gap-4"}
       [:div
        [:h2 {:class "text-2xl font-bold text-primary"} "🎓 Skill Gaps & Course Recommendations"]
        [:p {:class "text-sm text-base-content/70"}
         "Analyze missing skills between your CV and target positions to find high-impact courses."]]
       
       ;; Select dropdown to switch target job
       [:div {:class "form-control w-full md:w-72"}
        [:label {:class "label"}
         [:span {:class "label-text font-semibold"} "Target Job Position:"]]
        [:select {:class "select select-bordered w-full"
                  :value (or (:job_id selected-job) "")
                  :on-change (fn [e]
                               (let [val (.. e -target -value)
                                     job (first (filter #(= (str (:job_id %)) val) jobs))]
                                 (when job
                                   (swap! state assoc :selected-job job)
                                   (fetch-course-recommendations! state (:job_id job)))))}
         (if (empty? jobs)
           [:option {:value ""} "No jobs available (Search in Tab 2)"]
           (for [j jobs]
             ^{:key (:job_id j)}
             [:option {:value (:job_id j)} (:job_title j)]))]]]]

     ;; Main Content Area
     (if (nil? selected-job)
       [:div {:class "card bg-base-100 p-12 text-center text-base-content/50"}
        "Please select or match a job position in Tab 2 to view skill gap recommendations."]

       [:div {:class "space-y-6"}
        ;; Target Context Card
        [:div {:class "alert alert-neutral shadow-md"}
         [:div {:class "flex-1"}
          [:h4 {:class "font-bold text-lg"} (str "Analyzing gaps for: " (:job_title selected-job))]
          [:p {:class "text-xs opacity-70"} (str "Company: " (:company selected-job))]]]

        ;; Course List / Loading State
        (cond
          courses-loading?
          [:div {:class "flex flex-col items-center justify-center p-12 space-y-3"}
           [:span {:class "loading loading-spinner loading-lg text-primary"}]
           [:p {:class "text-sm text-base-content/70"} "Querying DuckDB vector embeddings..."]]

          (empty? recommended-courses)
          [:div {:class "alert alert-warning"}
           "No courses found meeting the gap thresholds. Either your CV matches this job completely, or no relevant courses exist in the database."]

          :else
          [:div {:class "grid grid-cols-1 gap-4"}
           (for [course recommended-courses]
             (let [job-relevance (dist->pct (:job_dist course))
                   cv-gap         (dist->pct (:cv_dist course))]
               ^{:key (:course_id course)}
               [:div {:class "card bg-base-100 shadow-md hover:shadow-lg transition-all p-6"}
                [:div {:class "flex flex-col md:flex-row justify-between items-start gap-4"}
                 
                 ;; Left: Course Info
                 [:div {:class "space-y-2 flex-1"}
                  [:div {:class "flex items-center gap-2"}
                   [:span {:class "badge badge-primary badge-outline"} (or (:provider course) "Online Course")]
                   [:h3 {:class "text-xl font-bold"} (:title course)]]
                  [:p {:class "text-sm text-base-content/80"} (:description course)]]

                 ;; Right: Metrics & Progress Gauges
                 [:div {:class "w-full md:w-64 bg-base-200 p-4 rounded-box space-y-3"}
                  ;; Metric 1: Job Relevance
                  [:div
                   [:div {:class "flex justify-between text-xs font-bold mb-1"}
                    [:span "Job Relevance"]
                    [:span {:class "text-success"} (str job-relevance "%")]]
                   [:progress {:class "progress progress-success w-full" :value job-relevance :max 100}]]

                  ;; Metric 2: New Ground / Unlearned Skill Gap
                  [:div
                   [:div {:class "flex justify-between text-xs font-bold mb-1"}
                    [:span "Unlearned Skill Gap"]
                    [:span {:class "text-info"} (str cv-gap "%")]]
                   [:progress {:class "progress progress-info w-full" :value cv-gap :max 100}]]]]]))])])]))
