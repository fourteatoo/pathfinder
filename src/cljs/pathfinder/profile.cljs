(ns pathfinder.profile
  (:require [cljs.reader :as reader]
            [clojure.string :as s]
            [reagent.core :as r]
            [pathfinder.state :refer [state]]
            [pathfinder.util :refer [encode-transit decode-transit api-post]]))


(defn select-city! [city-obj]
  (swap! state assoc 
         :location (:city city-obj)
         :latitude (:lat city-obj)
         :longitude (:lng city-obj)
         :city-suggestions []))

(defn fetch-city-suggestions! [query]
  (if (< (count query) 2)
    (swap! state assoc :city-suggestions [])
    (-> (api-post "/api/search-cities" {:query query})
        (.then (fn [res]
                 (if (.-ok res)
                   (.text res) #_(.json res)
                   (throw (js/Error. "City search failed")))))
        (.then (fn [data]
                 (let [results (decode-transit data) #_(js->clj data :keywordize-keys true)]
                   (swap! state assoc :city-suggestions results))))
        (.catch (fn [err]
                  (js/console.error "City suggestion error:" err)
                  (swap! state assoc :city-suggestions []))))))

(defn city-autocomplete []
  (let [suggestions (:city-suggestions @state)]
    [:div {:class "form-control relative"}
     [:label {:class "label font-semibold"} "City / Location"]
     [:input {:type "text"
              :class "input input-bordered w-full"
              :value (:location @state)
              :on-change (fn [e]
                           (let [q (.. e -target -value)]
                             (swap! state assoc :location q)
                             (fetch-city-suggestions! q)))}]
     
     ;; Dropdown suggestions list
     (when (seq suggestions)
       [:ul {:class "menu bg-base-200 w-full rounded-box absolute top-20 z-10 shadow-lg max-h-60 overflow-y-auto"}
        (for [c suggestions]
          ^{:key (:name c)}
          [:li [:a {:on-click #(select-city! c)} 
                (str (:city c) " (" (:country c) ")")]])])]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- add-experience-id-to-profile [profile]
  (update profile :experience
          (fn [exps]
            (mapv #(assoc % :id (or (:id %) (random-uuid))) exps))))

(defn parse-and-set-edn! [edn-str]
  (try
    (let [parsed (reader/read-string edn-str)]
      (if (map? parsed)
        (do
          (swap! state assoc :profile (add-experience-id-to-profile parsed))
          (js/alert "EDN successfully loaded into profile!"))
        (js/alert "EDN error: File content must be a Clojure map starting with {}")))
    (catch :default e
      (js/console.error "EDN parse error:" e)
      (js/alert (str "Invalid EDN format: " (.-message e))))))

(defn handle-file-upload! [event]
  (let [file (.. event -target -files (item 0))]
    (when file
      (let [reader (js/FileReader.)]
        (set! (.-onload reader)
              (fn [e]
                (let [text (.. e -target -result)]
                  (parse-and-set-edn! text))))
        (.readAsText reader file)))))

;; Helper to build the exact payload sent to /api/search-jobs or /api/tailor-cv
(defn get-cv-payload []
  (:profile @state))

;; A Form-2 component: the outer function runs ONCE to set up local state
(defn skills-input [skills]
  (let [last-skills (r/atom skills)
        local-str   (r/atom (s/join ", " skills))]
    (fn [skills]
      ;; React to external state changes (e.g., EDN file upload)
      (when (not= skills @last-skills)
        (reset! last-skills skills)
        (reset! local-str (s/join ", " skills)))

      [:div {:class "form-control"}
       [:label {:class "label font-semibold"} "Skills (comma-separated)"]
       [:input {:type "text"
                :class "input input-bordered w-full"
                :value @local-str
                :on-change #(reset! local-str (.. % -target -value))
                :on-blur (fn []
                           (let [parsed (->> (s/split @local-str #",")
                                             (map s/trim)
                                             (filter seq)
                                             vec)]
                             (swap! state assoc-in [:profile :skills] parsed)))}]])))

#_
(defn skills-input []
  (let [local-str (r/atom (s/join ", " (get-in @state [:profile :skills])))]
    (fn []
      [:div {:class "form-control"}
       [:label "Skills (comma-separated)"]
       [:input {:type "text"
                :value @local-str
                :on-change #(reset! local-str (.. % -target -value))
                :on-blur (fn []
                           (let [parsed (->> (s/split @local-str #",")
                                             (map s/trim)
                                             (filter seq)
                                             vec)]
                             (swap! state assoc-in [:profile :skills] parsed)))}]])))

(defn experience-editor [idx initial-exp]
  ;; 1. Setup local draft atom (runs once per mounted card)
  ;; Format bullets as a plain multi-line string for smooth editing
  (let [local-draft (r/atom (assoc initial-exp 
                                   :bullets-str (s/join "\n" (:bullets initial-exp []))))]
    ;; 2. Render function
    (fn [idx _]
      (let [draft @local-draft
            ;; Helper to update local draft
            set-field! (fn [k val] (swap! local-draft assoc k val))
            ;; Helper to push local draft back to global state
            commit! (fn []
                      (let [clean-bullets (->> (s/split-lines (:bullets-str @local-draft ""))
                                               (map s/trim)
                                               (filter seq)
                                               vec)
                            final-exp (-> @local-draft
                                          (dissoc :bullets-str)
                                          (assoc :bullets clean-bullets))]
                        (swap! state assoc-in [:profile :experience idx] final-exp)))]
        
        [:div {:class "p-4 bg-base-200 rounded-lg space-y-3 mb-3 border border-base-300"}
         [:div {:class "flex justify-between items-center"}
          [:h4 {:class "font-bold text-sm text-primary"} (str "Position #" (inc idx))]
          [:button {:class "btn btn-xs btn-error btn-outline"
                    :on-click #(swap! state update-in [:profile :experience] 
                                      (fn [exps] (vec (concat (subvec exps 0 idx) 
                                                              (subvec exps (inc idx))))))}
           "Remove"]]
         
         ;; Fields update LOCAL draft on change, and COMMIT to global state on blur
         [:div {:class "grid grid-cols-1 md:grid-cols-3 gap-3"}
          [:input {:type "text" :placeholder "Company"
                   :class "input input-bordered input-sm"
                   :value (:company draft)
                   :on-change #(set-field! :company (.. % -target -value))
                   :on-blur commit!}]
          
          [:input {:type "text" :placeholder "Role / Title"
                   :class "input input-bordered input-sm"
                   :value (:role draft)
                   :on-change #(set-field! :role (.. % -target -value))
                   :on-blur commit!}]
          
          [:input {:type "text" :placeholder "Years (e.g. 2021 - Present)"
                   :class "input input-bordered input-sm"
                   :value (:years draft)
                   :on-change #(set-field! :years (.. % -target -value))
                   :on-blur commit!}]]
         
         [:div
          [:label {:class "label text-xs font-semibold"} "Key Achievements / Bullets (one per line)"]
          [:textarea {:class "textarea textarea-bordered textarea-sm w-full font-mono"
                      :rows 3
                      :value (:bullets-str draft)
                      :on-change #(set-field! :bullets-str (.. % -target -value))
                      :on-blur commit!}]]]))))

#_(defn experience-editor [idx exp]
  [:div {:class "p-4 bg-base-200 rounded-lg space-y-3 relative mb-3 border border-base-300"}
   [:div {:class "flex justify-between items-center"}
    [:h4 {:class "font-bold text-sm text-primary"} (str "Position #" (inc idx))]
    [:button {:class "btn btn-xs btn-error btn-outline"
              :on-click #(swap! state update-in [:profile :experience] 
                                (fn [exps] (vec (concat (subvec exps 0 idx) (subvec exps (inc idx))))))}
     "Remove"]]
   
   [:div {:class "grid grid-cols-1 md:grid-cols-3 gap-3"}
    [:input {:type "text" :placeholder "Company"
             :class "input input-bordered input-sm"
             :value (:company exp)
             :on-change #(swap! state assoc-in [:profile :experience idx :company] (.. % -target -value))}]
    [:input {:type "text" :placeholder "Role / Title"
             :class "input input-bordered input-sm"
             :value (:role exp)
             :on-change #(swap! state assoc-in [:profile :experience idx :role] (.. % -target -value))}]
    [:input {:type "text" :placeholder "Years (e.g. 2021 - Present)"
             :class "input input-bordered input-sm"
             :value (:years exp)
             :on-change #(swap! state assoc-in [:profile :experience idx :years] (.. % -target -value))}]]
   
   [:div
    [:label {:class "label text-xs font-semibold"} "Key Achievements / Bullets (one per line)"]
    [:textarea {:class "textarea textarea-bordered textarea-sm w-full font-mono"
                :rows 3
                :value (s/join "\n" (:bullets exp))
                :on-change (fn [e]
                             (let [lines (s/split-lines (.. e -target -value))]
                               (swap! state assoc-in [:profile :experience idx :bullets] lines)))}]]])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn search-jobs! []
  (swap! state assoc :loading? true)
  (-> (api-post "/api/search-jobs"
                {:profile (:profile @state)
                 :location (:location @state)
                 :latitude (js/parseFloat (:latitude @state))
                 :longitude (js/parseFloat (:longitude @state))
                 :geo-radius (js/parseInt (:geo-radius @state))})
      (.then (fn [res]
               (if (.-ok res)
                 (.text res) #_(.json res)
                 (throw (js/Error. (str "HTTP error " (.-status res)))))))
      (.then (fn [data]
               (let [jobs (decode-transit data) #_(js->clj data :keywordize-keys true)]
                 (swap! state assoc :jobs jobs :loading? false :active-tab :jobs))))
      (.catch (fn [err]
                (js/console.error "Search failed:" err)
                (js/alert (str "Search failed: " (.-message err)))
                (swap! state assoc :loading? false)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn profile-tab []
  (let [profile (:profile @state)
        exps (get profile :experience [])]
    [:div {:class "max-w-4xl mx-auto p-6 space-y-6"}
     
     ;; Top Demo Bar: Fast Upload & Quick-Load Demo Data
     [:div {:class "card bg-neutral text-neutral-content p-4 shadow-lg"}
      [:div {:class "flex flex-col sm:flex-row justify-between items-center gap-4"}
       [:div
        [:h3 {:class "font-bold"} "🚀  Quick Fill"]
        [:p {:class "text-xs opacity-80"} "Upload an existing .edn file to instantly populate candidate profile."]]
       
       [:div {:class "flex gap-2"}
        [:label {:class "btn btn-accent btn-sm"}
         "📁 Upload EDN File"
         [:input {:type "file" :accept ".edn" :class "hidden" :on-change handle-file-upload!}]]]]]

     ;; Main Structured Form Card
     [:div {:class "card bg-base-100 shadow-xl p-6 space-y-4"}
      [:h2 {:class "card-title text-2xl mb-2"} "Candidate Profile Data"]
      
      [:div {:class "form-control"}
       [:label {:class "label font-semibold"} "Full Name"]
       [:input {:type "text"
                :class "input input-bordered w-full"
                :value (:name profile)
                :on-change #(swap! state assoc-in [:profile :name] (.. % -target -value))}]]
      
      [:div {:class "form-control"}
       [:label {:class "label font-semibold"} "Professional Summary"]
       [:textarea {:class "textarea textarea-bordered h-24 w-full"
                   :value (:summary profile)
                   :on-change #(swap! state assoc-in [:profile :summary] (.. % -target -value))}]]
      
      [skills-input (get-in @state [:profile :skills])]
      
      ;; Experience List Header & Add Button
      [:div {:class "pt-2"}
       [:div {:class "flex justify-between items-center mb-2"}
        [:label {:class "label font-semibold"} "Work Experience"]
        [:button {:class "btn btn-xs btn-outline btn-primary"
                  :on-click #(swap! state update-in [:profile :experience] 
                                    conj {:company "" :role "" :years "" :bullets []})}
         "+ Add Position"]]
       
       (for [[idx exp] (map-indexed vector exps)]
         ^{:key (:id exp)}
         [experience-editor idx exp])]

      ;; Location Controls Integration
      [:div {:class "divider"} "2. Location & Radius"]

      [:div {:class "grid grid-cols-1 md:grid-cols-3 gap-4"}
       [city-autocomplete]

       ;; Read-only coordinates (auto-populated by select-city!)
       [:div {:class "form-control"}
        [:label {:class "label font-semibold"} "Coordinates (Lat / Lon)"]
        [:div {:class "flex gap-2"}
         [:input {:type "number" :read-only true
                  :class "input input-bordered w-1/2 bg-base-200"
                  :value (:latitude @state)}]
         [:input {:type "number" :read-only true
                  :class "input input-bordered w-1/2 bg-base-200"
                  :value (:longitude @state)}]]]
       
       [:div {:class "form-control"}
        [:label {:class "label font-semibold"} (str "Search Radius: " (:geo-radius @state) " km")]
        [:input {:type "range" :min "5" :max "500" :step "5"
                 :class "range range-primary"
                 :value (:geo-radius @state)
                 :on-change #(swap! state assoc :geo-radius (.. % -target -value))}]]]

      
      [:div {:class "card-actions justify-end mt-6"}
       [:button {:class "btn btn-primary btn-lg"
                 :on-click search-jobs!
                 :disabled (:loading? @state)}
        (if (:loading? @state)
          [:span {:class "loading loading-spinner"} "Searching..."]
          "🔍 Search Jobs →")]]]]))
