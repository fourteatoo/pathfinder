(ns pathfinder.jdbc
  (:require
   [clojure.string :as s]
   [next.jdbc :as jdbc]
   [next.jdbc.prepare :as prepare]
   [next.jdbc.result-set :as rs]
   [next.jdbc.sql :as sql]
   [tablecloth.api :as tc]
   [next.jdbc.connection :as connection]
   [tech.v3.dataset.sql :as ds-sql])
  (:import [com.zaxxer.hikari HikariDataSource]))

(def duckdb-path "../data/embeddings.duckdb.jdbc")

(def ^:private db-spec {:dbtype "duckdb" :dbname duckdb-path})

(def db-pool
  (delay (connection/->pool HikariDataSource db-spec)))

(defn- db-conn []
  #_(jdbc/get-connection @db-pool)
  @db-pool)



(defn- infer-sql-type
  "Inspects the first element of a vector to determine the SQL type name."
  [v]
  (let [sample (first v)]
    (cond (nil? sample) "VARCHAR" ; safe default for empty vectors
          (float? sample) "FLOAT"   ; or "DOUBLE" depending on your target DB precision
          (integer? sample) "INTEGER"
          (boolean? sample) "BOOLEAN"
          (string? sample) "VARCHAR"
          :else "VARCHAR")))

(extend-protocol prepare/SettableParameter
  clojure.lang.IPersistentVector
  (set-parameter [v ^java.sql.PreparedStatement stmt ^long idx]
    (let [conn     (.getConnection stmt)
          sql-type (infer-sql-type v)
          sql-arr  (.createArrayOf conn sql-type (to-array v))]
      (.setArray stmt idx sql-arr)))

  float/1
  (set-parameter [v ^java.sql.PreparedStatement stmt ^long idx]
    (let [conn (.getConnection stmt)
          ;; Box floats to Objects for java.sql.Array creation
          boxed-arr (object-array (map object-array [v]))
          ;; createArrayOf expects Object[]
          sql-arr (.createArrayOf conn "FLOAT" (to-array (map float v)))]
      (.setArray stmt idx sql-arr)))

  tech.v3.dataset.Text
  (set-parameter [^tech.v3.dataset.Text v ^java.sql.PreparedStatement stmt ^long ix]
    (.setString stmt ix (.toString v))))

;; Automatically convert DuckDB native arrays back into clean Clojure
;; vectors
(extend-protocol rs/ReadableColumn
  org.duckdb.DuckDBArray
  (read-column-by-label [^org.duckdb.DuckDBArray v _]
    (vec (.getArray v)))
  (read-column-by-index [^org.duckdb.DuckDBArray v _ _]
    (vec (.getArray v))))


(defn execute [statement & parameters]
  (jdbc/execute! (db-conn) (cons statement parameters)))

(defn setup-vss []
  (execute "INSTALL vss;")
  (execute "LOAD vss;")
  (execute "INSTALL spatial;")
  (execute "LOAD spatial;"))

(comment (setup-vss))

(defn execute-batch
  ([statement parameter-groups]
   (execute-batch (db-conn) statement parameter-groups))
  ([tx statement parameter-groups]
   (jdbc/execute-batch! tx statement parameter-groups {})))



(defn- ->sql-name [x]
  (-> (name x)
      s/trim
      s/lower-case
      (s/replace #"[^\w\s_-]" "")
      (s/replace #"[- ]" "_")))

(defn create-table
  "Create an SQL table of name `table-name` and with colomns as passed
  in a list of maps.  Each map should at least have a :name and
  a :type keyword.  If :key is also present it should be an SQL key
  type"
  [table-name columns]
  (execute (str "CREATE TABLE IF NOT EXISTS " table-name
                " ("
                (s/join ", "
                        (map (fn [c]
                               (str (->sql-name (:name c))
                                    " " (name (:type c))
                                    (when (:key c)
                                      (str " " (name (:key c))
                                           " KEY"))))
                             columns))
                ")")))

(comment
  (describe-table "cities")
  (execute "select count(*) from cities"))

(defn rename-table [from to]
  (execute (str "ALTER TABLE " from " RENAME TO " to)))

(defn drop-table [table-name]
  (execute (str "DROP TABLE IF EXISTS " table-name)))

(defn rename-column [table from to]
  (execute "ALTER TABLE " table " RENAME COLUMN " from " TO " to))

#_(defn insert-dataset
  ([ds]
   (let [ds (ds-sql/sanitize ds)]
     (ds-sql/ensure-table! (db-conn) ds)
     (ds-sql/insert-dataset! (db-conn) ds)))
  ([ds table-name]
   (insert-dataset (tc/set-dataset-name ds table-name))))

(defn insert-dataset
  ([ds]
   (insert-dataset ds (tc/dataset-name ds)))
  ([ds table]
   (let [info (tc/info ds)
         columns (:col-name info)
         insert-statement (str "INSERT INTO " table " ("
                               (s/join ", " (map ->sql-name columns))
                               ") VALUES ("
                               (s/join ", " (repeat (count columns) "?")) ")")]
     (jdbc/with-transaction [tx (db-conn)]
       (with-open [stmt (jdbc/prepare tx [insert-statement])]
         (try (jdbc/execute-batch! stmt (tc/rows ds :as-vecs) {:batch-size 1000})
              (catch Exception e
                (throw (ex-info "exception" {:ds ds} e)))))))))

(defn list-tables []
  (jdbc/execute! (db-conn) ["SHOW TABLES"]))

(defn describe-table [table]
  (jdbc/execute! (db-conn) [(str "DESCRIBE " table)]))

(comment
  (list-tables)
  (describe-table "cities"))

(defn find-jobs-by-neighbours [embedding & {:keys [limit]
                                            :or {limit 5}}]
  ;; embeddings should all be the same length
  (jdbc/execute! (db-conn) [(str "SELECT *,
                               array_cosine_distance(emb, [0.10, 0.80, -0.40]::FLOAT["
                               (count embedding)
                               "]) AS semantic_distance
                               FROM jobs_embedded
                               ORDER BY semantic_distance ASC
                               LIMIT " limit)]))

(defn load-table [table rows & {:keys [batch-size]
                                :or {batch-size 5000}}]
  (let [columns (keys (first rows))
        sql (str "INSERT INTO " table 
                 " (" (s/join ", " (map ->sql-name columns)) ") "
                 " VALUES (" (s/join ", " (repeat (count columns) "?")) ")")]
    (jdbc/with-transaction [tx (db-conn)]
      (->> rows
           (map #(mapv % columns))
           (partition-all batch-size)
           (map-indexed vector)
           (run! (fn [[idx batch]]
                   (println (str "upload batch #" (inc idx) " (" (count batch) " rows)"))
                   (execute-batch tx sql batch)))))))

(defn insert-job-embedding [job-id embedding]
  (execute "INSERT INTO job_embeddings (job_id, embedding) VALUES (?, ?)"
           job-id embedding))

(defn insert-course-embedding [course-id embedding]
  (execute "INSERT INTO course_embeddings (course_id, embedding) VALUES (?, ?)"
           course-id embedding))

(defn insert-skill-embedding [skill-id embedding]
  (execute "INSERT INTO skill_embeddings (skill_id, embedding) VALUES (?, ?)"
           skill-id embedding))

(comment
  (jdbc/execute! (db-conn) ["SELECT * from jobs_embedded"]))

(defn plan [select & parameters]
  (jdbc/plan (db-conn) (cons select parameters)))

(defn- red->seq [rs]
  ((fn step []
     (lazy-seq
      (if (.next rs)
        ;; Extract the row map using next.jdbc's native builders
        (cons (.next rs) (step))
        ;; Clean up when done
        (do (.close rs)
            nil))))))

(defn fetch-unembedded-job-descriptions
  "Return all the jobs that do not have an embedding, yet.  This is
  useful for when the embeddings are loaded piecemeal.  Return a
  Reducible."
  [& [num-rows]]
  (plan (str "SELECT * from job_descriptions j LEFT JOIN job_embeddings e ON j.job_id = e.job_id WHERE e.job_id IS NULL"
             (when num-rows
               (str " LIMIT " num-rows)))))

(defn count-unembedded-job-descriptions
  "Return the number of jobs that do not have an embedding, yet."
  []
  (execute "SELECT count(*) from job_descriptions j LEFT JOIN job_embeddings e ON j.job_id = e.job_id WHERE e.job_id IS NULL"))

(defn count-job-descriptions
  "Return the number of jobs that do not have an embedding, yet."
  []
  (execute "SELECT count(*) from job_descriptions"))

(comment
  (count-unembedded-job-descriptions)
  (count-job-descriptions))

(defn fetch-all-job-descriptions
  "Return a Reducible"
  [& [num-rows]]
  (plan (str "SELECT * from job_descriptions"
             (when num-rows
               (str " LIMIT " num-rows)))))

(defn fetch-all-job-descriptions*
  "Return a sequence of rows"
  [& [num-rows]]
  (execute (str "SELECT * from job_descriptions"
             (when num-rows
               (str " LIMIT " num-rows)))))

(defn fetch-unembedded-courses
  "Return all the courses that do not have an embedding, yet.  This is
  useful for when the embeddings are loaded piecemeal.  Return a
  Reducible."
  [& [num-rows]]
  (plan (str "SELECT * from courses c LEFT JOIN course_embeddings e ON c.course_id = e.course_id WHERE e.course_id IS NULL"
             (when num-rows
               (str " LIMIT " num-rows)))))

(defn fetch-unembedded-skills
  "Return all the skills that do not have an embedding, yet.  This is
  useful for when the embeddings are loaded piecemeal.  Return a
  Reducible."
  [& [num-rows]]
  (plan (str "SELECT * from skills s LEFT JOIN skill_embeddings e ON s.skill_id = e.skill_id WHERE e.skill_id IS NULL"
             (when num-rows
               (str " LIMIT " num-rows)))))

(defn fetch-all-courses
  "Return a Reducible"
  [& [num-rows]]
  (plan (str "SELECT * from courses"
             (when num-rows
               (str " LIMIT " num-rows)))))

(defn fetch-all-courses*
  "Return a sequence of record"
  [& [num-rows]]
  (execute (str "SELECT * from courses"
                (when num-rows
                  (str " LIMIT " num-rows)))))

(comment
  (fetch-all-courses* 10)
  (plan "SELECT * from job_descriptions")
  (count (eduction (map :job-id)))
  (count (read-all-job-descriptions)))


(comment
  (defn save-dataset-to-db!
    "Saves a Tablecloth dataset to a relational database table.
   Creates the table based on column types if it does not exist."
    [ds table-name db-spec]
    ;; Open connection with auto-commit false for faster batch insertion
    (with-open [conn (jdbc/get-connection db-spec {:auto-commit false})]
      
      ;; Sanitize dataset column names (e.g. kebab-case -> snake_case) and attach table name
      (let [sql-ds (-> ds
                       (tc/set-dataset-name (name table-name))
                       (ds-sql/sanitize-dataset-names-for-sql))]
        (ds-sql/ensure-table! (jdbc/db-conn) sql-ds)
        (ds-sql/insert-dataset! conn sql-ds)

        (.commit conn))))

  ;; Usage:
  (save-dataset-to-db! my-dataset "users" db-spec))

