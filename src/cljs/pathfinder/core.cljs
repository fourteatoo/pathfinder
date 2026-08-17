(ns pathfinder.core
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdom-client]
            [pathfinder.state :refer [state]]
            [pathfinder.navbar :refer [navbar]]
            [pathfinder.profile :refer [profile-tab]]
            [pathfinder.jobs :refer [jobs-tab]]
            [pathfinder.courses :refer [courses-tab]]
            [pathfinder.util :as util]))

;; --- MAIN LAYOUT SWITCH ---
(defn main-app []
  [:div {:class "min-h-screen bg-base-200"}
   [util/mascot-overlay]
   [navbar]
   (case (:active-tab @state)
     :profile [profile-tab]
     :jobs    [jobs-tab]
     :courses [courses-tab]
     [profile-tab])])

;; --- REAGENT 1.3 MOUNT LIFECYCLE ---
(defonce root-instance (atom nil))

(defn ^:dev/after-load mount-root []
  (when-let [el (.getElementById js/document "app")]
    (if-let [root @root-instance]
      (rdom-client/render root [main-app])
      (let [new-root (rdom-client/create-root el)]
        (reset! root-instance new-root)
        (rdom-client/render new-root [main-app])))))

(defn init! []
  (mount-root))

