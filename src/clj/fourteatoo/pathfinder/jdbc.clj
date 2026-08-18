(ns fourteatoo.pathfinder.jdbc
  (:require
   [clojure.string :as s]
   [mount.core :as mount]
   [next.jdbc :as jdbc]
   [next.jdbc.connection :as connection]
   [next.jdbc.prepare :as prepare]
   [next.jdbc.result-set :as rs]
   [tablecloth.api :as tc])
  (:import
   (com.zaxxer.hikari HikariDataSource)))

(def duckdb-path "../data/embeddings.duckdb.jdbc")

(def ^:private db-spec {:dbtype "duckdb" :dbname duckdb-path})

(mount/defstate db-pool
  :start (connection/->pool HikariDataSource db-spec))

(defn- db-conn []
  db-pool)

(comment
  (mount/start)
  (db-conn))

(defn- infer-sql-type
  "Inspects the first element of a vector to determine the SQL type name."
  [v]
  (let [sample (first v)]
    (cond (nil? sample) "VARCHAR"
          (float? sample) "FLOAT"
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
          boxed-arr (object-array (map object-array [v]))
          sql-arr (.createArrayOf conn "FLOAT" (to-array (map float v)))]
      (.setArray stmt idx sql-arr)))

  tech.v3.dataset.Text
  (set-parameter [^tech.v3.dataset.Text v ^java.sql.PreparedStatement stmt ^long ix]
    (.setString stmt ix (.toString v))))

;; Automatically convert DuckDB native arrays back into Clojure
;; vectors
(extend-protocol rs/ReadableColumn
  org.duckdb.DuckDBArray
  (read-column-by-label [^org.duckdb.DuckDBArray v _]
    (vec (.getArray v)))
  (read-column-by-index [^org.duckdb.DuckDBArray v _ _]
    (vec (.getArray v))))


(defn execute [statement & parameters]
  (jdbc/execute! (db-conn) (cons statement parameters) jdbc/unqualified-snake-kebab-opts))

(defn execute-one [statement & parameters]
  (jdbc/execute-one! (db-conn) (cons statement parameters) jdbc/unqualified-snake-kebab-opts))

(defn setup-vss []
  (execute "INSTALL vss;")
  (execute "LOAD vss;")
  (execute "INSTALL spatial;")
  (execute "LOAD spatial;"))

(comment (setup-vss))

;; define vss a separate state to avoid recursive dependency with
;; db-pool
(mount/defstate vss
  :start (setup-vss))

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

(defn plan [select & parameters]
  (jdbc/plan (db-conn) (cons select parameters)))

