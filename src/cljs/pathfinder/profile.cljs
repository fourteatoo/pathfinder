(ns pathfinder.profile
  (:require [pathfinder.state :refer [state]]))

(defn search-jobs! []
  (swap! state assoc :loading? true)
  (-> (js/fetch "/api/search-jobs"
                #js {:method "POST"
                     :headers #js {"Content-Type" "application/json"}
                     :body (js/JSON.stringify #js {:cv_text (:cv-text @state)})})
      (.then #(.json %))
      (.then (fn [json-data]
               (let [jobs (js->clj json-data :keywordize-keys true)]
                 (swap! state assoc :jobs jobs :loading? false :active-tab :jobs))))
      (.catch (fn [err]
                (js/console.error "Search failed" err)
                (swap! state assoc :loading? false)))))

(defn profile-tab []
  [:div {:class "max-w-4xl mx-auto p-6"}
   [:div {:class "card bg-base-100 shadow-xl p-6"}
    [:h2 {:class "card-title text-2xl mb-2"} "Candidate Resume"]
    [:p {:class "text-sm text-base-content/70 mb-4"}
     "Paste your CV or enter free-text experience to match against jobs in DuckDB."]
    
    [:textarea {:class "textarea textarea-bordered h-64 w-full font-mono p-4"
                :placeholder "Paste CV text or EDN map here..."
                :value (:cv-text @state)
                :on-change #(swap! state assoc :cv-text (.. % -target -value))}]
    
    [:div {:class "card-actions justify-end mt-6"}
     [:button {:class "btn btn-primary"
               :on-click search-jobs!
               :disabled (:loading? @state)}
      (if (:loading? @state)
        [:span {:class "loading loading-spinner"} "Searching..."]
        "Save Profile & Search Jobs →")]]]])
