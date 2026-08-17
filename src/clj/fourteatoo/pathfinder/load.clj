(ns fourteatoo.pathfinder.load
  (:require
   [tablecloth.api :as tc]
   [clojure.string :as s]
   [clojure.java.io :as io]
   [camel-snake-kebab.core :as csk]
   [clojure.edn :as edn]
   [fourteatoo.pathfinder.jdbc :as jdbc])
  (:import
   (java.security MessageDigest)
   (org.tukaani.xz XZInputStream)))


(defn- xz-reader [file-path]
  (-> (io/file file-path)
      (io/input-stream)
      (XZInputStream.)
      (io/reader)))

(defn- xz-file? [name]
  (s/ends-with? (str name) ".xz"))

(defn- csv-reader [file-name]
  (if (xz-file? file-name)
    (xz-reader file-name)
    (io/reader file-name)))

(defn load-dataset [file-path & {:as opts}]
  (let [opts (merge {:key-fn csk/->kebab-case-keyword
                     :file-type :csv}
                    opts)]
    (with-open [in (csv-reader file-path)]
      (tc/dataset in opts))))

#_(defn- load-dataset [path & {:keys [num-rows]}]
  (if (xz-file? path)
    (load-xz-csv-dataset path :num-rows num-rows)
    (tc/dataset path {:num-rows num-rows})))

(defn load-edn [file-path]
  (with-open [in (io/input-stream file-path)]
    (-> (repeatedly #(edn/read in))
        (take-while some?))))


;; Map Tablecloth/tech.ml.dataset datatypes to DuckDB SQL types
(defn- tc-type->duckdb-type [dt]
  (case dt
    (:int8 :int16 :int32 :uint8 :uint16) "INT"
    (:int64 :uint32 :uint64)             "BIGINT"
    (:float32 :float)                    "FLOAT"
    (:float64 :double)                   "DOUBLE"
    :boolean                             "BOOLEAN"
    :packed-local-date                   "DATE"
    (:string :text :symbol)              "VARCHAR"
    :zoned-date-time "DATETIME"
    ;; that's the only type of vector we handle in this program
    :persistent-vector "FLOAT[]"))

(defn- csv-header->keyword [x]
  (-> (name x)
      s/trim
      s/lower-case
      (s/replace #"[^\w\s_-]" "")
      (s/replace #"[\s_]" "-")
      keyword))

(defn- dataset-sql-columns [ds]
  (let [info (tc/info ds)]
    (map (fn [name type]
           {:name (csv-header->keyword name)
            :type (tc-type->duckdb-type type)})
         (:col-name info)
         (:datatype info))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- csv-columns [path]
  ;; 20 rows should be enough to get an impression of what kind of
  ;; data is in the columns and infer their type.
  (->> (load-dataset path :num-rows 10)
       dataset-sql-columns))

(defn create-table-from-dataset
  ([ds]
   (create-table-from-dataset ds (tc/dataset-name ds)))
  ([ds table-name]
   (->> (dataset-sql-columns ds)
        (jdbc/create-table table-name))))

