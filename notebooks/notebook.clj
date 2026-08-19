^:kindly/hide-code
(ns notebook
  {:clay {:quarto-target-path "docs/notebook"
          :quarto {:format {:revealjs {:theme "default"
                                       :slide-number true}}}
          :browse false
          :live-reload false
          :title "Pathfinder Notebook"
          :show false}}
  (:require
   [fourteatoo.pathfinder.jdbc :as jdbc]
   [fourteatoo.pathfinder.jobs :as jobs]
   [fourteatoo.pathfinder.load :as load]
   [fourteatoo.pathfinder.prep :as prep]
   [fourteatoo.pathfinder.trends :as trends]
   [fourteatoo.pathfinder.cities :as cities]
   [fourteatoo.pathfinder.core]    ; to make sure everything is loaded
   [mount.core :as mount]
   [scicloj.clay.v2.api :as clay]
   [scicloj.kindly.v4.kind :as kind]
   [tablecloth.api :as tc]
   [fourteatoo.pathfinder.courses :as courses]))


^:kindly/hide-code
(def started
  ;; Throughout the notebook we use stuff that needs the DB. And we do
  ;; so at load time!  So all the namespaces need to be intitialised
  ;; now.  This definition in itself doesn't serve other purpose than
  ;; hiding the code and its returned value from the output notbook.
  (mount/start))

^:kindly/hide-code
(defn make-notebook []
  ;; this function can be called by leiningen
  (clay/make! {:source-path "notebooks/notebook.clj"
               :add-to-buffer false
               :browse false
               :live-reload false
               :show false})
  (mount/stop)
  (System/exit 0))


^:kindly/hide-code
(defn start-clay-interactively []
  (clay/start! {:source-path *file*
                :base-target-path "docs"
                :live-reload true
                :serve? true
                ;; Pure notebook HTML (NO Quarto/Reveal.js!)
                :format [:html]}))

^:kindly/hide-code
(comment (clay/stop!))

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

;;; # Career Pathfinder Engine PoC
;;
;;
;; This is the companion notebook to the Pathfinder app.  It is meant
;; to give some insights about the nature and shape of the data and
;; give some cluse about the design decisions that have been taken
;; along the way.

^:kindly/hide-code
(kind/image "resources/public/images/boyscout.png")



;; ## A brief look at the raw data
;;
;; We basically use 4 datasets coming from the usual places:
;; Kaggle, Huggingface, StackOverflow.  The jobs, the courses, world
;; cities geo information, and the StackOverflow 2025 survey.

;; ### The job offers are from Kaggle
;; https://www.kaggle.com/datasets/asaniczka/linkedin-data-engineer-job-postings

(def jobs
  (load/load-dataset (jobs/job-postings-path)))

(ds-info jobs)

;; here is an example
(-> (tc/random jobs 5)
    (tc/drop-columns [:job-summary]))

;; The jobs contain synthetic data that doesn't always make much
;; sense for our specific presentation:

(-> (load/load-dataset (jobs/job-postings-path) :column-whitelist ["job_location" "search_city" "search_country"])
    (tc/rename-columns {:job-location :location})
    (tc/drop-missing [:location])
    (tc/group-by [:location])
    (tc/aggregate {:count tc/row-count})
    (tc/order-by :count :desc))

;; We'll take care of that, augmenting this dataset with European
;; cities and geo locations, below.

;; ### The courses are from Kaggle

(def courses
  (load/load-dataset (courses/courses-path)))

(ds-info courses)

;; here is an example

(tc/random courses 5)


;; ### The cities from SimpleMaps
;;
;; They will "complete" the job offers and let us map between
;; different naming conventions
;; https://simplemaps.com/data/world-cities

(def cities
  (load/load-dataset cities/worldcities-path))

(ds-info cities)

;; here's an example

(tc/random cities 10)


;; ### The market trends from StackOverflow
;;
;; We gather insights on the hottest technologies from StackOverflow,
;; which famously performs a yearly survey.

(def survey
  (load/load-dataset (trends/tech-survey-path) :num-rows 20))

(ds-info survey)

;; here's an example

(tc/random survey 5)

;; The data will need major massaging before being useful to
;; Pathfinder.


;; ## What ends up in the database
;;
;; Some of this data may conceivably grow a bit too large to keep in
;; memory, so we store the clean and adjusted data in DuckDB.  DuckDB
;; has been chosen for its native support of vector embeddings.

;; This is how the jobs table looks like

(count-table-rows "jobs")

(-> (jdbc/execute "select * from jobs limit 5")
    tc/dataset
    (tc/drop-columns [:embedding])
    (tc/map-columns :description
                    [:description]
                    (fn [desc]
                      (if (> (count desc) 200)
                        (str (subs desc 0 200) " [...]")
                        desc))))

;; The job offers have been relocated.  That is, they have been
;; assigned a major European city according to its population.  This
;; gives predicatble distribution across familiar places.

(-> (jdbc/execute "select location,latitude,longitude from jobs")
    tc/dataset
    jobs/plot-job-offers)

;; The courses data looks like this

(count-table-rows "courses")

(-> (jdbc/execute "select * from courses limit 5")
    tc/dataset
    (tc/drop-columns [:embedding]))

;; The technology trends is a distillation of the StackOverflow survey
;; data.  Once reduced to it's bare essentials it isn't that unwieldy
;; any more.


(ds-info @trends/trends-ds)

;; here is an example

(tc/random @trends/trends-ds 10)

;; ###  How to interpret the data

;; The desirability index may require some explanation.  This index
;; tells how a technology is desired (sparks geunine user interest)
;; compared to how often people are paid to use it:
;;
;;  - The Growth Drivers (Ratio > 1.1): Languages like Rust, Go, or
;;    specialized AI frameworks. These represent technologies where
;;    community excitement outstrips current enterprise adoption.
;;    This has likely a high future demand and talent scarcity, which
;;    typically commands a salary premium.
;;
;;  - The Market Anchors (Ratio 0.4 – 0.7): The baseline industry
;;    giants like JavaScript, Java, and SQL. They have massive
;;    have-count values but lower ratios. This demonstrates
;;    the "Enterprise Lock-in" concept; these are the bedrock of the
;;    job market where real-world hiring volume is guaranteed, even if
;;    the developer "hype" has moved on.
;;
;;  - The Atrophy Zone (Ratio < 0.3): The legacy stacks or fading
;;    frameworks. If a technology has a low count, low salary, and a
;;    plunging ratio, it tells a clear story of a shrinking ecosystem
;;    where finding modern, lucrative roles will become increasingly
;;    difficult.

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;



;; # The Tech Landscape
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

;; 
;; Which exposes the typical IT conundrum that sees fun techonology
;; as the least in demand (by employers) and the most adopted
;; technology among the least desirable (by developers).

;; 
;; ## The Pay Gap
;; 
;; Her is how the surveys distribute incomes across the globe:
^:kindly/hide-code
(trends/plot-survey-geo-distribution
 (trends/join-so-data-with-centroids
  (trends/extract-country-overview (trends/tech-survey-path))))

;; ## Implementation details
;;
;; StackOverflow's data covers a large number of countries and it
;; looks different from region to region.  For this reason the indices
;; calculated by the app are limited to the area where the user looks
;; for job opportunities.  For each skill that is recognised in the
;; course a number of indices are calculated.  Eventually those
;; indices are compounded (averaged) for the course.
;;
;; The calculated indexed are:
;;   - desirability (appeal)
;;   - use in actual job setting (adoption)
;;   - expected salary or, rather, the median salary of people using
;;     the same skill professionally
;;
;; 
;; # Architecture diagrams
;;
;; Odd looking boxes connected by arrows

;; ## Data sources and their role within the app

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

      subgraph StoredEmbeddings[Pre-computed Vector Embeddings]
        E_Jobs[Job Embeddings]
        E_Courses[Course Embeddings]
      end

      subgraph External[Runtime Inputs]
        Files[/External CV Files/]
        UI_Coords[/UI Coordinates/]
      end

      subgraph Dynamic[On-the-Fly Processing]
        E_CVs[CV Embedding Engine]
        Map_Country[Coord-to-Country Lookup]
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

pipeline-diagram

;; ## User interface
;;
;; How the app should look and behave, but never does.


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

ui-diagram

;; EOF
