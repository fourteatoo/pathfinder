(ns pathfinder.navbar
  (:require [pathfinder.state :refer [state]]))

(defn navbar []
  (let [curr-tab (:active-tab @state)]
    [:div {:class "navbar bg-base-100 shadow-lg px-6"}
     [:div {:class "flex-1"}
      [:a {:class "btn btn-ghost text-xl font-bold"} "🧭  Pathfinder Career Explorer"]]
     [:div {:class "flex-none"}
      [:div {:class "tabs tabs-boxed"}
       [:a {:class (str "tab " (when (= curr-tab :profile) "tab-active"))
            :on-click #(swap! state assoc :active-tab :profile)} 
        "1. Profile"]
       [:a {:class (str "tab " (when (= curr-tab :jobs) "tab-active"))
            :on-click #(swap! state assoc :active-tab :jobs)} 
        "2. Job Matching"]
       [:a {:class (str "tab " (when (= curr-tab :courses) "tab-active"))
            :on-click #(swap! state assoc :active-tab :courses)} 
        "3. Skill Gaps"]]]]))
