(ns fourteatoo.pathfinder.api
  (:require [org.httpkit.server :as server]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [fourteatoo.pathfinder.search :as search]
            [fourteatoo.pathfinder.cv :as cv]
            [clojure.java.io :as io]))

(defn api-routes [req]
  (let [uri (:uri req)
        method (:request-method req)
        body (:body req)]
    (cond
      ;; Search jobs against CV vector
      (and (= method :post) (= uri "/api/search-jobs"))
      (let [cv-text (get body "cv_text")]
        {:status 200
         :body (search/search-jobs cv-text)})

      ;; Tailor CV for a specific job offer via Groq LPU
      (and (= method :post) (= uri "/api/tailor-cv"))
      (let [cv (get body "cv")
            job (get body "job")
            groq-key (System/getenv "GROQ_API_KEY")]
        {:status 200
         :body {:tailored_cv (cv/generate-tailored-cv groq-key cv job)}})

      ;; Recommend courses that bridge the CV -> Job gap
      (and (= method :post) (= uri "/api/recommend-courses"))
      (let [cv-text (get body "cv_text")
            job-id  (get body "job_id")]
        {:status 200
         :body (search/recommend-courses cv-text job-id)})

      ;; Fallback to index.html for SPA page loads / refreshes
      :else
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (slurp (io/resource "public/index.html"))})))

(def app
  (-> api-routes
      wrap-json-body
      wrap-json-response
      (wrap-resource "public")
      wrap-content-type))

(defonce server-instance (atom nil))

(defn start! []
  (reset! server-instance (server/run-server #'app {:port 8080}))
  (println "Server running on http://localhost:8080"))

(defn stop! []
  (when-not (nil? @server-instance)
    (@server-instance :timeout 100)
    (reset! server-instance nil)
    (println "Server stopped.")))
