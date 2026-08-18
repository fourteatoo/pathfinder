^:kindly/hide-code
(ns slides
  {:clay {:quarto-target-path "docs"
          :quarto {:format {:revealjs {:theme "default"
                                       :slide-number true}
                            ;; Add pptx configs here
                            :pptx {}}}
          :title "Pathfinder"}}
  (:require
   [fourteatoo.pathfinder.jdbc :as jdbc]
   [fourteatoo.pathfinder.trends :as trends]
   [fourteatoo.pathfinder.core]  ; to make sure everything is loaded
   [mount.core :as mount]
   [scicloj.clay.v2.api :as clay]
   [scicloj.kindly.v4.kind :as kind]
   [tablecloth.api :as tc]))


^:kindly/hide-code
(comment
;; Thoughout the slides we use stuff that needs the DB.  And we do so
;; at load time!  So all the namespaces need to be intitialised now.
  )

^:kindly/hide-code
 (def started (mount/start))

^:kindly/hide-code
(defn make-slides []
  ;; This function can be called by Leiningen.
  ;; WARNING: a Clay server is already started in the notebook, and the
  ;; slides do not need a hot reload.  So do not clay/start in this
  ;; module.  Just manually use clay/make when necessary.
  (clay/make! {:source-path "notebooks/slides.clj"
               :add-to-buffer false
               :browse false
               :format [:quarto :revealjs]
               :show false})
  (mount/stop)
  (System/exit 0))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

^:kindly/hide-code
(kind/hiccup
 ;; A bit of style: this avoids splitting and wrapping tables
 [:style "
   /* 1. Force container to scroll horizontally instead of clipping/stacking */
   .clay-dataset, .kind-table, div.table-wrapper, .table-responsive {
     overflow-x: auto !important;
     max-width: 100% !important;
   }

   /* 2. Set hard character boundaries on table cells */
   table th, table td {
     max-width: 80ch !important;        /* Cap width at roughly 80 characters */
     min-width: 10ch !important;        /* Prevent tiny squeezed columns */
     white-space: normal !important;    /* Allow text inside cell to wrap */
     word-break: break-word !important; /* Break long unbroken strings/urls */
     vertical-align: top !important;
   }

   /* 3. Keep empty or small cells compact */
   table {
     border-collapse: collapse !important;
     width: max-content !important;      /* Prevent table from shrinking to container */
   }
 "])

^:kindly/hide-code
(kind/md "
<style>
  /* Limit maximum height of Mermaid diagrams in slides */
  .reveal .mermaid svg {
    max-height: 480px !important;
    width: auto !important;
  }
</style>
")

^:kindly/hide-code ^:kindly/hide-value
(defn ds-info-fragmented [ds]
  (kind/fragment
   [(str (tc/row-count ds) " rows")
    (tc/select-columns (tc/info ds) [:col-name :datatype :n-valid :n-missing])]))

^:kindly/hide-code ^:kindly/hide-value
(defn ds-info [ds]
  (kind/hiccup
    [:div {:style {:display "flex" 
                   :flex-direction "column" 
                   :gap "1rem" 
                   :margin-bottom "1rem"}}
     [:div {:style {:background "#f4f4f5" 
                    :padding "0.75rem 1rem" 
                    :border-radius "6px" 
                    :font-weight "600"}}
      (str "Total Rows: " (tc/row-count ds))]
     (tc/select-columns (tc/info ds) [:col-name :datatype :n-valid :n-missing])]))

^:kindly/hide-code
(defn count-table-rows [table]
  (first
   (vals
    (jdbc/execute-one (str "select count(*) from " table)))))




;; # The Problem
;;
;; What does one do when she's looking for a job?  A quick visit to
;; Indeed, LinkedIn, or other professional platform usually is the
;; start.

;; ---
;;
;; But that's just the start.
;;

^:kindly/hide-code
(kind/hiccup
 [:div {:style {:width "700px"
                :position "absolute"
                :bottom 0
                :right 0
                :margin "0 auto"}}
  (kind/image "resources/public/images/seeker-crop.png"
              #_{:style {:max-width "300px"
                     :height "auto"
                     :display "block"
                     :margin "0 auto"}})])

;; ---

;;
;; Then comes the lengthy reading, even the decoding, the profile
;; tweaking, the application, etc.  And that's when one is the perfect
;; fit for the job.  But what about when she isn't?
;;
;; Enter Pathfinder.

^:kindly/hide-code
(kind/hiccup
 [:div {:style {:position "absolute"
                ;; :bottom -30
                :right 0
                :margin "0 auto"}}
  (kind/image "resources/public/images/boyscout.png")])



;; # Project Overview
;;
;; The Career Pathfinder Engine is a data-driven decision system. Its
;; goal is to bridge the gap between a job seeker's current profile,
;; market offer, statistical realities, and targeted educational
;; opportunities.
;;
;; ## How it works
;;
;; Unlike naive job search engines, Pathfinder uses:
;; 
;;  - Semantic job matches (no regex, keywords, etc)
;;  - Semantic course matches (ditto)
;;  - Semantic rating in relation to skills
;;  - GenAI for CV tailoring
;;  - Market metadata (where available)

;; ---

^:kindly/hide-code
(kind/image "resources/public/images/desktop.jpg")



;; # The Market
;;
;; Why do we serve market data?
;;
;; Course recommendations based on semantic similarity alone don't
;; give the entire picture.  We want to know how a course can
;; potentially transform our life, in the context of what the market
;; offers and the community's opinion.

;; ## Segmentation
;; 
;; The StackOverflow's survey covers a whole lot of technologies
;; across several countries.  We take care of taking a picture of a
;; localised reality as to avoid bunching together wildly different
;; data groups.

;; ## For Germany:

^:kindly/hide-code
(trends/plot-trends
 (tc/select-rows @trends/trends-ds
                 (fn [row]
                   (and (= (:year row) 2025)
                        (> (:have-count row) 400)
                        (= "Germany" (:country row))))))

;; ## For USA:

^:kindly/hide-code
(trends/plot-trends
 (tc/select-rows @trends/trends-ds
                 (fn [row]
                   (and (= (:year row) 2025)
                        (> (:have-count row) 900)
                        (= "United States of America" (:country row))))))

;; ---
;; 
;; Which exposes the typical IT conundrum that sees fun techonology
;; as the least in demand (by employers) and the most adopted
;; technology among the least desirable (by developers).

;; ---
;; 
;; ## The Pay Gap
;; 
;; How the StackOverflow's survey distribute income across the
;; the regions
^:kindly/hide-code
(trends/plot-survey-geo-distribution
 (trends/join-so-data-with-centroids
  (trends/extract-country-overview (trends/tech-survey-path))))

;; # Architecture diagrams
;;
;; Because it's not just code that can look like spaghetti

;; ## Data flow
;;
;; Although the data comes from disparate sources, it is all ingested
;; in DuckDB tables for easy access.

^:kindly/hide-code
(def pipeline-diagram
  (kind/mermaid
   "graph LR
      subgraph Sources[External Data Sources]
        K[Kaggle]
        HF[Hugging Face]
        SO[StackOverflow]
        SM[SimpleMaps]
      end

      subgraph DuckDB[DuckDB Storage]
        T_Jobs[(Jobs Table)]
        T_Courses[(Courses Table)]
        T_Stats[(SO Stats Table)]
        T_Cities[(Cities Table)]
      end

      subgraph StoredEmbeddings[Vector Embeddings]
        E_Jobs[Job Embeddings]
        E_Courses[Course Embeddings]
      end

      subgraph External[Runtime Inputs]
        Files[/CV Files/]
        UI_Coords[/Geo Coord/]
      end

      subgraph Dynamic[On-the-Fly Processing]
        E_CVs[Embedding Engine]
        Map_Country[Country Coord Lookup]
      end

      K --> T_Jobs
      K --> T_Courses
      HF -.-> T_Jobs
      SO --> T_Stats
      SM --> T_Cities

      T_Cities -. Enrichment .-> T_Jobs

      T_Jobs --> E_Jobs
      T_Courses --> E_Courses

      Files --> E_CVs
      UI_Coords --> Map_Country
      T_Cities --> Map_Country
      Map_Country -. Country Query .-> T_Stats

      classDef db fill:#fff3e0,stroke:#f57c00;
      classDef embed fill:#e8eaf6,stroke:#3f51b5;
      classDef ext fill:#fffde7,stroke:#fbc02d;
      classDef dyn fill:#ede7f6,stroke:#7e57c2;
      classDef optional fill:#f5f5f5,stroke:#9e9e9e,stroke-dasharray:4;
      classDef default stroke-width:3px,stroke:#333;
      linkStyle default stroke-width:2px;

      class DuckDB db;
      class StoredEmbeddings embed;
      class External ext;
      class Dynamic dyn;
      class HF optional;
   "))

;; <div style="width: 100%; max-width: 100%; font-size: 0.9em;">
^:kindly/hide-code
pipeline-diagram
;; </div>

;; ## User interface
;;
;; How the app should behave, but never does


^:kindly/hide-code
(def ui-diagram
  (kind/mermaid
   "graph TD
      subgraph App[Application UI]

        subgraph Pane1[Pane 1: CV Input]
          CV_In[Enter / Upload CV]
        end

        subgraph Pane2[Pane 2: Job Querying]
          Job_Q[Query Job Offers]
          Job_List[Display Matching Jobs]
        end

        subgraph Pane3[Pane 3: Course Matching]
          Course_List[Relevant Courses & Tech Stats]
        end

      end

      subgraph Engine[Backend & Embeddings]
        Match_Jobs[CV <--> Job Vector Match]
        Match_Courses[Job + CV <--> Course Vector Match]
      end

      CV_In --> Match_Jobs
      Job_Q --> Match_Jobs
      Match_Jobs --> Job_List

      Job_List --> Match_Courses
      CV_In --> Match_Courses
      Match_Courses --> Course_List

      style App fill:#f5f5f5,stroke:#616161,stroke-width:1px
      style Pane1 fill:#e1f5fe,stroke:#0288d1
      style Pane2 fill:#e8f5e9,stroke:#388e3c
      style Pane3 fill:#f3e5f5,stroke:#ab47bc
      classDef default stroke-width:3px,stroke:#333
      linkStyle default stroke-width:2px;
   "))

^:kindly/hide-code
ui-diagram

;; # Additional Material

;; GitHub repo at
;; https://github.com/fourteatoo/pathfinder
;;
;; GitHub Pages at
;; https://fourteatoo.github.io/pathfinder

;; <div style="text-align: center;">
;;   <img src="https://api.qrserver.com/v1/create-qr-code/?size=250x250&data=https://github.com/fourteatoo/pathfinder" alt="Scan to view repo" style="width: 250px; height: 250px;">
;; </div>
