(ns pathfinder.util
  (:require
   [cognitect.transit :as t]))

(defn- to-json [x]
  (js/JSON.stringify (clj->js x)))


(defn skill-tag-cloud [skills-list & {:keys [max-visible] :or {max-visible 6}}]
  (when (seq skills-list)
    (let [visible-skills (take max-visible skills-list)
          hidden-skills  (drop max-visible skills-list)
          hidden-count   (count hidden-skills)
          tooltip-text   (clojure.string/join ", " hidden-skills)]
      [:div {:class "space-y-1.5"}
       [:p {:class "text-xs font-semibold text-base-content/50"} "Skills:"]
       [:div {:class "flex flex-wrap gap-1.5 items-center"}
        ;; 1. Render primary visible skills
        (for [skill visible-skills]
          ^{:key skill}
          [:span {:class "badge badge-ghost badge-sm text-base-content/70 border-base-300"} skill])
        
        ;; 2. Render "+n more" badge with DaisyUI hover tooltip
        (when (pos? hidden-count)
          [:div {:class "tooltip tooltip-top cursor-pointer"
                 :data-tip tooltip-text}
           [:span {:class "badge badge-ghost badge-sm text-xs font-medium text-primary/80 border-primary/20 bg-primary/5"}
            (str "+" hidden-count " more")]])]])))

(defn mascot-overlay
  [& {:keys [position] :or {position :bottom-right}}]
  (let [pos-class (case position
                    :top-right    "top-4 right-4"
                    :bottom-right "bottom-4 right-4"
                    "bottom-4 right-4")]
    [:div {:class (str "fixed z-40 pointer-events-none opacity-80 transition-opacity " pos-class)}
     [:img {:src "/images/boyscout.png"
            :alt ""
            ;; Increased from w-16 to w-32 (or arbitrary w-[140px])
            :class "w-28 h-28 md:w-36 md:h-36 object-contain drop-shadow-md"}]]))

#_
(defn mascot-overlay
  "Renders a non-scrolling mascot fixed to the bottom right corner."
  [& {:keys [position] :or {position :bottom-right}}]
  (let [pos-class (case position
                    :top-right    "top-4 right-4"
                    :bottom-right "bottom-4 right-4"
                    "bottom-4 right-4")]
    [:div {:class (str "fixed z-40 pointer-events-none opacity-80 transition-opacity " pos-class)}
     [:img {:src "/images/boyscout.png"
            :alt "App Mascot"
            :class "w-20 h-20 md:w-24 md:h-24 object-contain drop-shadow-md"}]]))


(defn render-link [text link]
  (when link
    [:a {:href link
         :target "_blank"
         :rel "noopener noreferrer"
         :class "btn btn-ghost btn-xs text-primary gap-1"}
     text
     ;; Simple inline external link SVG icon
     [:svg {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" 
            :stroke-width "1.5" :stroke "currentColor" :class "w-3 h-3"}
      [:path {:stroke-linecap "round" :stroke-linejoin "round" 
              :d "M13.5 6H5.25A2.25 2.25 0 0 0 3 8.25v10.5A2.25 2.25 0 0 0 5.25 21h10.5A2.25 2.25 0 0 0 18 18.75V10.5m-10.5 6L21 3m0 0h-5.25M21 3v5.25"}]]]))

(defn trend-icon [trend-kw]
  (case trend-kw
    :up   [:span {:style {:color "#16a34a" :margin-left "4px"}} "▲"]
    :down [:span {:style {:color "#dc2626" :margin-left "4px"}} "▼"]
    :flat [:span {:style {:color "#6b7280" :margin-left "4px"}} "●"]
    ;; if it's garbage, point it out
    [:span {:style {:color "#ff0000" :margin-left "4px"}} "?"]))

(defn metric-badge [label value-fmt trend]
  [:div.metric-container
   [:span.metric-label label]
   [:span.metric-value value-fmt]
   [trend-icon trend]])

(defn decode-transit [transit-string]
  (let [r (t/reader :json)]
    (t/read r transit-string)))

(defn encode-transit [data]
  (let [w (t/writer :json)]
    (t/write w data)))

(defn api-post [url body]
  (js/fetch url
            (clj->js
             {:method "POST"
              :headers {"Content-Type" "application/transit+json"
                        "Accept"       "application/transit+json"}
              :body (encode-transit body)})))
