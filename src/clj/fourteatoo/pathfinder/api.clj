(ns fourteatoo.pathfinder.api
  (:require [org.httpkit.server :as server]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            #_[ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.transit :refer [wrap-transit-body wrap-transit-response]]
            [fourteatoo.pathfinder.search :as search]
            [fourteatoo.pathfinder.cv :as cv]
            [clojure.java.io :as io]
            [mount.core :as mount]
            [camel-snake-kebab.core :as csk]
            [fourteatoo.pathfinder.trends :as trends]
            [fourteatoo.pathfinder.cities :as cities]))


(defn handle-city-search [req]
  (let [query (get-in req [:body :query] "")
        results (search/search-cities query)]
    {:status 200
     :headers {"Content-Type" "application/edn"}
     :body (pr-str results)}))

(comment
  (search/search-cities "Milan"))

(defn handle-course-search [req]
  (let [body (:body req)
        profile (:profile body)
        latitude (:latitude body)
        longitude (:longitude body)
        country (cities/find-country-from-coords latitude longitude)
        job-id  (:job body)]
    {:status 200
     :body (map #(trends/enrich-course-with-market-data % country)
                (search/filter-recommendations-by-distance
                 (search/recommend-courses-for-job
                  (search/fetch-job job-id) profile)))}))

(defn- handle-job-search [req]
  (let [body (:body req)
        profile (:profile body)
        latitude (:latitude body)
        longitude (:longitude body)
        geo-radius (:geo-radius body)]
    (prn 'body body)                    ; -wcp17/08/26
    {:status 200
     :body (search/search-jobs profile
                               :latitude latitude :longitude longitude
                               :geo-radius geo-radius)}))

#_(->> 
   ;; JS cannot handle BigInts
   (map #(update % :job-id str)))

(comment
  (search/search-jobs {:name "Viktor Kovács", :summary "Embedded systems specialist with expertise in C/C++, RTOS, microcontroller firmware (ARM Cortex-M), and industrial automation protocols (CAN bus, Modb...", :skills ["C" "C++" "ARM Assembly" "FreeRTOS" "Embedded Linux" "CAN bus" "SPI/I2C" "GDB" "Python" "CMake"], :experience [{:company "AutoControl Automotive", :role "Principal Firmware Engineer", :years "2018 - Present", :bullets ["Developed ISO 26262 functional safety-compliant C firmware for automotive ECU controllers." "Optimized FreeRTOS task scheduling to guarantee deterministic response time under 1ms."], :id #uuid "82ac26f8-991a-45f8-aec6-334ea45677f7"} {:company "RoboTech Industrial", :role "Senior Embedded Engineer", :years "2012 - 2018", :bullets ["Built embedded Linux BSPs and device drivers for ARM-based robotic arms." "Implemented custom communication protocol over CAN bus."], :id #uuid "fe580509-ec77-4458-a0ed-ce3b4999c85a"} {:company "MicroDev Hungary", :role "Embedded Developer", :years "2008 - 2012", :bullets ["Programmed 8-bit and 32-bit microcontrollers in C for smart metering hardware."], :id #uuid "e418b2ff-4439-4725-846c-0f381d335b35"}]}
                      :latitude 50.1106 :longitude 8.6822 :geo-radius 1000))

(comment
  (search/search-cities "Frankfurt")
  (search/search-jobs "python developer" :latitude 50.1106 :longitude 8.6822 :geo-radius 500))

(defn- handle-cv-tailor [req]
  (let [body (:body req)
        cv (:cv body)
        job-id (:job body)]
    {:status 200
     :body {:tailored-cv
            (cv/generate-tailored-cv cv (search/fetch-job job-id))}}))

(defn api-routes [req]
  (let [uri (:uri req)
        method (:request-method req)
        body (:body req)]
    (cond
      ;; Search jobs against CV vector
      (and (= method :post) (= uri "/api/search-jobs"))
      (handle-job-search req)

      ;; Tailor CV for a specific job offer
      (and (= method :post) (= uri "/api/tailor-cv"))
      (handle-cv-tailor req)

      (and (= method :post) (= uri "/api/search-cities"))
      (handle-city-search req)

      ;; Recommend courses that bridge the CV -> Job gap
      (and (= method :post) (= uri "/api/recommend-courses"))
      (handle-course-search req)

      ;; Fallback to index.html for SPA page loads / refreshes
      :else
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (slurp (io/resource "public/index.html"))})))

(def app
  (-> api-routes
      (wrap-transit-body {:keywords? true})
      (wrap-transit-response {:encoding :json})
      (wrap-resource "public")
      wrap-content-type))

(defn start! []
  (server/run-server #'app {:port 8080}))

(defn stop! [server]
  (when-not (nil? server)
    (server :timeout 100)))

(mount/defstate server
  :start (start!)
  :stop (stop! server))



(comment
  (mount/start)
  (mount/stop))
