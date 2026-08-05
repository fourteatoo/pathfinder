(ns pathfinder.jobs
  (:require [pathfinder.state :refer [state]]))

(defn tailor-cv! [job]
  (swap! state assoc :loading? true)
  (-> (js/fetch "/api/tailor-cv"
                #js {:method "POST"
                     :headers #js {"Content-Type" "application/json"}
                     :body (js/JSON.stringify #js {:cv (:cv-edn @state) :job job})})
      (.then #(.json %))
      (.then (fn [res]
               (let [data (js->clj res :keywordize-keys true)]
                 (swap! state assoc :tailored-cv (:tailored_cv data) :loading? false))))
      (.catch (fn [err]
                (js/console.error "Tailoring failed" err)
                (swap! state assoc :loading? false)))))

(defn jobs-tab []
  (let [{:keys [jobs selected-job tailored-cv loading?]} @state]
    [:div {:class "grid grid-cols-12 gap-6 p-6"}
     
     ;; Left Column: Matched Job Offers (4 cols)
     [:div {:class "col-span-12 lg:col-span-4 space-y-4"}
      [:div {:class "flex justify-between items-center"}
       [:h3 {:class "text-lg font-bold"} "Matched Positions"]
       [:span {:class "badge badge-outline"} (str (count jobs) " found")]]
      
      (if (empty? jobs)
        [:div {:class "alert alert-info"} 
         "No matching jobs loaded yet. Search from the Profile tab!"]
        (for [job jobs]
          ^{:key (:job_id job)}
          [:div {:class (str "card bg-base-100 shadow-md hover:shadow-lg cursor-pointer transition-all p-4 "
                             (when (= (:job_id selected-job) (:job_id job)) "ring-2 ring-primary"))
                 :on-click #(swap! state assoc :selected-job job :tailored-cv nil)}
           [:h4 {:class "font-bold text-lg"} (:job_title job)]
           [:p {:class "text-sm text-base-content/70"} (:company job)]
           [:div {:class "flex justify-between items-center mt-3"}
            [:div {:class "badge badge-accent"} 
             (str (int (* (- 1 (or (:match_dist job) 0)) 100)) "% Match")]
            [:span {:class "text-xs text-base-content/60"} 
             (str (or (:geo_dist_km job) 0) " km away")]]]))]

     ;; Right Column: Job Detail & Groq Tailored Resume (8 cols)
     [:div {:class "col-span-12 lg:col-span-8"}
      (if selected-job
        [:div {:class "card bg-base-100 shadow-xl p-6 space-y-6"}
         [:div {:class "flex justify-between items-start"}
          [:div
           [:h2 {:class "text-2xl font-bold"} (:job_title selected-job)]
           [:p {:class "text-base-content/70"} (:company selected-job)]]
          [:button {:class "btn btn-secondary"
                    :on-click #(tailor-cv! selected-job)
                    :disabled loading?}
           (if loading?
             [:span {:class "loading loading-spinner"} "Generating..."]
             "⚡ Tailor CV for Job (Groq)")]]

         [:div {:class "divider"}]

         [:div {:class "space-y-2"}
          [:h4 {:class "font-bold text-lg"} "Job Description"]
          [:p {:class "text-base-content/80 whitespace-pre-wrap"} (:job_description selected-job)]]

         (when tailored-cv
           [:div {:class "mt-6 p-6 bg-base-300 rounded-box space-y-4"}
            [:h3 {:class "text-xl font-bold text-secondary"} "Groq Tailored Resume (Markdown)"]
            [:pre {:class "whitespace-pre-wrap font-sans bg-base-100 p-4 rounded-lg text-sm"} tailored-cv]])]

        [:div {:class "card bg-base-100 p-12 text-center text-base-content/50"}
         "Select a job offer from the list on the left to view details and tailor your CV."])]]))
