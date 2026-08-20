(ns pathfinder.mascot)

(defn empty-state-scout []
  [:div {:class "hero min-h-[300px] bg-base-200/50 rounded-box border border-dashed border-base-300 my-6"}
   [:div {:class "hero-content text-center flex-col md:flex-row gap-6"}
    [:img {:src "/images/boyscout.png"
           :alt "Skill Scout Mascot"
           :class "w-32 h-32 object-contain drop-shadow-md animate-bounce-subtle"}]
    [:div {:class "text-left max-w-md"}
     [:h3 {:class "text-lg font-bold text-base-content"} "Ready to scout your next step?"]
     [:p {:class "text-sm text-base-content/70 mt-1"}
      "Select a job offer on the left to analyze skill gaps and find target course recommendations."]
     [:div {:class "badge badge-primary badge-outline mt-3 gap-1 text-xs"}
      "⭐ Earn your next skill badge"]]]])

(defn floating-scout-companion []
  (r/with-let [show-tip? (r/atom false)]
    [:div {:class "fixed bottom-6 right-6 z-50 flex flex-col items-end gap-2"}
     ;; Speech bubble tip on click or hover
     (when @show-tip?
       [:div {:class "chat chat-end animate-fade-in"}
        [:div {:class "chat-bubble chat-bubble-primary text-xs shadow-lg max-w-xs"}
         "Tip: Courses with the 🔥 icon feature skills with over 80% market demand in 2025!"]])
     
     ;; Mascot Avatar Button
     [:button {:class "btn btn-circle btn-lg border-2 border-primary shadow-xl bg-base-100 hover:scale-105 transition-transform p-1"
               :on-click #(swap! show-tip? not)}
      [:img {:src "/images/boyscout.png"
             :alt "Scout Companion"
             :class "w-full h-full object-contain rounded-full"}]]]))


