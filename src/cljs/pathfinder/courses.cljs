(ns pathfinder.courses
  (:require [reagent.core :as r]
            [clojure.string :as s]
            [pathfinder.state :refer [state]]
            [pathfinder.util :as util :refer [to-json]]
            [clojure.set :as set]))

;; --- API CALL HELPER ---
(defn fetch-course-recommendations! [job-id]
  (swap! state assoc :courses-loading? true)
  (-> (js/fetch "/api/recommend-courses"
                (clj->js {:method "POST"
                          :headers {"Content-Type" "application/json"}
                          :body (to-json {:profile (:profile @state)
                                          :job job-id
                                          ;; these are for the trend stats
                                          :latitude (:latitude @state)
                                          :longitude (:longitude @state)})}))
      (.then #(.json %))
      (.then (fn [json-data]
               (let [courses (js->clj json-data :keywordize-keys true)]
                 (swap! state assoc 
                        :recommended-courses courses 
                        :courses-loading? false
                        :active-tab :courses))))
      (.catch (fn [err]
                (js/console.error "Course retrieval failed" err)
                (swap! state assoc :courses-loading? false)))))

;; --- FORMATTING HELPERS ---
(defn- format-currency [val]
  (if (and val (number? val))
    (str "$" (js/Math.round (/ val 1000)) "k")
    "N/A"))

(defn- format-ratio [val]
  (if (and val (number? val))
    (str (js/Math.round (* val 100)) "%")
    "N/A"))

(defn- ->pct [x]
  (js/Math.round (* x 100)))

(defn- dist->pct
  "Converts a cosine/vector distance (0.0 to 1.0) into a percentage match (0% to 100%)."
  [dist]
  (if (nil? dist)
    0
    (int (max 0 (min 100 (->pct (- 1.0 dist)))))))

(defn render-rating [rate reviews]
  (when rate
    [:div {:class "flex items-center gap-1 text-amber-500 text-sm font-semibold"}
     [:span "★"]
     [:span (js/Number.prototype.toFixed.call rate 1)]
     (when (and reviews (> reviews 0))
       [:span {:class "text-xs text-base-content/60 font-normal"} 
        (str "(" reviews " reviews)")])]))

(defn skill-badge
  "Renders a single row with a dotted leader line connecting name to metrics."
  [item]
  (let [skill-name   (if (map? item) (:skill item) item)
        metric       (when (map? item) (or (:metric item) item))
        salary       (:median-salary metric)
        appeal       (:desirability metric)
        adoption     (:adoption metric)
        
        highly-usable? (and adoption (> adoption 0.5))
        cool?          (and appeal (> appeal 0.8))]
    [:div {:class "flex items-center text-xs py-1.5 gap-2"}
     ;; Skill Name & Badges
     [:div {:class "flex items-center gap-1 font-medium shrink-0"}
      [:span skill-name]
      (when cool?
        [:span {:class "text-[10px]" :title "High developer interest!"} "😎"])
      (when highly-usable?
        [:span {:class "text-[10px]" :title "Broadly adopted!"} "🔥"])]
     
     ;; Dotted Leader Line
     [:div {:class "grow border-b border-dotted border-base-content/20 relative top-[-2px]"}]
     
     ;; Metrics (Flush Right - Whole elements conditionally rendered)
     [:div {:class "flex items-center gap-3 text-right shrink-0"}
      (when appeal
        [:span {:class (str (if cool? "text-warning font-semibold" "opacity-70") 
                            " tooltip tooltip-left cursor-help")
                :data-tip "Developer interest ratio (Want vs Have)"}
         (str "Appeal: " (format-ratio appeal))])
      
      (when adoption
        [:span {:class (str (if highly-usable? "text-info font-semibold" "opacity-70") 
                            " tooltip tooltip-left cursor-help")
                :data-tip "Percentage of professional developers using this tech"}
         (str "Adoption: " (format-ratio adoption))])
      
      (when salary
        [:span {:class "text-success font-semibold min-w-[55px]"}
         (format-currency salary)])]]))

(defn course-skills-toggle-list
  "Renders compact badges by default. Clicking the area replaces it inline with 
   a full scrollable list of localized metrics. Clicking again collapses it back."
  [skill-metrics & {:keys [max-visible max-salary avg-desirability] :or {max-visible 5}}]
  (r/with-let [expanded? (r/atom false)]
    (when (seq skill-metrics)
      (let [total-count  (count skill-metrics)
            visible      (take max-visible skill-metrics)
            hidden-count (- total-count max-visible)]
        [:div {:class "pt-2"}
         
         ;; --- HEADER & TOP-LEVEL AGGREGATES ---
         [:div {:class "flex justify-between items-center mb-1.5 gap-2"}
          [:p {:class "text-xs font-semibold text-base-content/60"}
           (if @expanded?
             "Skills & Local Market Metrics (click to collapse):"
             "Skills Taught (click to view market metrics):")]

          [:div {:class "flex items-center gap-3 ml-auto"}
           ;; Overall Course Aggregates (Flush Right)
           (when (and max-salary (not @expanded?))
             [:span {:class "text-xs font-semibold text-success"}
              (str "Top: " (format-currency max-salary))])
           
           (when (and avg-desirability (not @expanded?))
             [:span {:class "text-xs opacity-70 tooltip tooltip-left cursor-help"
                     :data-tip "Average market desirability index across course skills"}
              (str "Avg Des: " (format-ratio avg-desirability))])

           ;; Expand / Collapse Button
           (when (or (pos? hidden-count) @expanded?)
             [:button {:class "btn btn-ghost btn-xs text-xs text-primary p-0 h-auto min-h-0"
                       :on-click (fn [e]
                                   (.stopPropagation e)
                                   (swap! expanded? not))}
              (if @expanded? "Collapse" (str "+" hidden-count " more"))])]]

         (if-not @expanded?
           ;; --- COMPACT VIEW (SHOWS ALL VISIBLE SKILLS) ---
           [:div {:class "flex flex-wrap gap-1.5 items-center cursor-pointer group"
                  :on-click #(reset! expanded? true)}
            (for [item visible]
              (let [skill-name (if (map? item) (:skill item) item)]
                ^{:key skill-name}
                [:span {:class "badge badge-ghost border-base-300 text-xs group-hover:border-primary/50 transition-colors"} 
                 skill-name]))
            
            (when (pos? hidden-count)
              [:span {:class "badge badge-neutral text-xs group-hover:badge-primary transition-colors"} 
               (str "+" hidden-count " more")])]

           ;; --- DETAILED INLINE EXPANDED VIEW ---
           [:div {:class "bg-base-200/60 rounded-box p-3 border border-base-300 space-y-1.5 max-h-64 overflow-y-auto cursor-pointer"
                  :on-click #(reset! expanded? false)}
            (for [item skill-metrics]
              (let [skill-name (if (map? item) (:skill item) item)]
                ^{:key skill-name}
                [skill-badge item]))])]))))

(defn skills-string->list [s]
  (->> (s/split s #",")
       (map s/trim)
       (remove s/blank?)))

(defn complete-skill-metrics [skill-metrics all-skills]
  (let [metrics-by-tech (into {} (map (juxt :skill identity) skill-metrics))]
    (map (fn [skill]
           (or (get metrics-by-tech skill)
               {:skill skill}))
         all-skills)))

(defn stat-gauge
  "Renders a compact label + progress bar pair."
  [{:keys [label value-str pct color-class] :or {color-class "progress-info"}}]
  [:div {:class "space-y-0.5"}
   [:div {:class "flex justify-between text-xs font-bold leading-tight"}
    [:span label]
    [:span {:class (if (= color-class "progress-success") "text-success" "text-info")} 
     (or value-str (str pct "%"))]]
   (when pct
     [:progress {:class (str "progress " color-class " w-full h-1.5 block") 
                 :value pct 
                 :max 100}])])

(defn- format-additional-info [course]
  (str "Schedule: " (:schedule course)
       "\nInstructors: " (:instructors course)
       \newline))

(defn courses-tab []
  (let [{:keys [jobs selected-job recommended-courses courses-loading?]} @state]
    [:div {:class "max-w-6xl mx-auto p-6 space-y-6"}
     ;; Header & Job Selector Banner
     [:div {:class "card bg-base-100 shadow-xl p-6"}
      [:div {:class "flex flex-col md:flex-row md:items-center justify-between gap-4"}
       [:div
        [:h2 {:class "text-2xl font-bold text-primary"} "🎓 Skill Gaps & Course Recommendations"]
        [:p {:class "text-sm text-base-content/70"}
         "Analyze missing skills between your CV and target positions to find high-impact courses."]]
       
       ;; Target Job Dropdown
       [:div {:class "form-control w-full md:w-72"}
        [:label {:class "label"}
         [:span {:class "label-text font-semibold"} "Target Job Position:"]]
        [:select {:class "select select-bordered w-full"
                  :value (str (or (:id selected-job) (:job-id selected-job) ""))
                  :on-change (fn [e]
                               (let [val (.. e -target -value)
                                     job (first (filter #(= (str (or (:id %) (:job-id %))) val) jobs))]
                                 (when job
                                   (swap! state assoc :selected-job job)
                                   (fetch-course-recommendations! (or (:id job) (:job-id job))))))}
         (if (empty? jobs)
           [:option {:value ""} "No jobs available (Search in Tab 2)"]
           (for [j jobs]
             (let [jid (str (or (:id j) (:job-id j)))
                   jtitle (or (:title j) (:job-title j))]
               ^{:key jid}
               [:option {:value jid} jtitle])))]]]]

     ;; Main Content Area
     (if (nil? selected-job)
       [:div {:class "card bg-base-100 p-12 text-center text-base-content/50"}
        "Please select or match a job position in Tab 2 to view skill gap recommendations."]

       [:div {:class "space-y-6"}
        ;; Target Context Card
        [:div {:class "alert alert-neutral shadow-md"}
         [:div {:class "flex-1"}
          [:h4 {:class "font-bold text-lg"} 
           (str "Analyzing gaps for: " (or (:title selected-job) (:job-title selected-job) (:job_title selected-job)))]
          [:p {:class "text-xs opacity-70"} 
           (str "Company: " (:company selected-job))]]]

        ;; Course List States
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
             (let [c-id          (str (or (:course-id course) (:title course)))
                   job-relevance (dist->pct (:job-dist course))
                   cv-gap        (dist->pct (:cv-dist course))
                   avg-salary (format-currency (:market-avg-salary course))
                   market-adoption (->pct (:market-avg-adoption course))
                   appeal (->pct (:market-avg-desirability course))
                   skills-data (complete-skill-metrics (:skill-metrics course)
                                                       (skills-string->list (:gained-skills course)))]
               ^{:key c-id}
               [:div {:class "card bg-base-100 shadow-md hover:shadow-lg transition-all p-6"}
                [:div {:class "flex flex-col md:flex-row justify-between items-start gap-6"}
                 
                 ;; Left Column: Metadata & Inline Toggle Skills
                 [:div {:class "space-y-3 flex-1"}
                  [:div {:class "flex flex-wrap items-center gap-2 text-xs"}
                   [:span {:class "font-bold text-primary"} (or (:institution course)
                                                                (:provider course)
                                                                "Online Provider")]
                   [:span {:class "text-base-content/40"} "•"]
                   [:span {:class "badge badge-primary badge-outline"} 
                    (or (:learning-product course) "Course")]
                   (when-let [lvl (:level course)]
                     [:span {:class "badge badge-secondary badge-outline"} lvl])
                   (when-let [dur (:duration course)]
                     [:span {:class "badge badge-ghost"} (str "⏱ " dur)])
                   (when-let [subj (:subject course)]
                     [:span {:class "badge badge-ghost"} subj])
                   [:div {:class "ml-auto"}
                    [util/render-link "Course page" (:url course)]]]
                  
                  [:div {:class "space-y-1"}
                   [:h3 {:class "text-xl font-bold tooltip tooltip-right"
                         :data-tip (:instructors course)}
                    (or (:title course) (:course-title course))]
                   [render-rating (:rate course) (:reviews course)]]

                  [:p {:class "text-sm whitespace-pre-line text-base-content/80 line-clamp-6 hover:line-clamp-none transition-all cursor-pointer"}
                   (:description course)]

                  ;; Skill cloud with inline toggle replacement
                  (when (seq skills-data)
                    [course-skills-toggle-list skills-data :max-visible 10])]

                 ;; Right Column: Relevance & Gap Gauges
                 [:div {:class "w-full md:w-64 bg-base-200 p-4 rounded-box space-y-2.5 shrink-0"}
                  [stat-gauge {:label "Job Relevance"  :pct job-relevance   :color-class "progress-success"}]
                  [stat-gauge {:label "Skill Gap"      :pct cv-gap}]
                  [stat-gauge {:label "Market Adoption":pct market-adoption}]
                  [stat-gauge {:label "Appeal"         :pct appeal}]
                  [stat-gauge {:label "Salary Exp"     :value-str avg-salary}]]]]))])])]))
