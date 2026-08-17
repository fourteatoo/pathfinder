(defproject io.github.fourteatoo/pathfinder "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://github.io/fourteatoo/pathfinder"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.5"]
                 [camel-snake-kebab "0.4.3"]
                 [scicloj/tablecloth "8.024"]
                 [org.scicloj/tableplot "1-beta17"]
                 [techascent/tech.ml.dataset.sql "7.029"]
                 ;; [generateme/fastmath "2.3.0"]
                 [net.clojars.savya/embeddings-clj "0.5.0"]
                 ;; alternative interface to DuckDB
                 [com.github.seancorfield/next.jdbc "1.3.1118"]
                 [com.zaxxer/HikariCP "7.1.0"]
                 [org.duckdb/duckdb_jdbc "1.5.5.1"]
                 [com.github.seancorfield/honeysql "2.7.1437"]
                 [org.tukaani/xz "1.12"]
                 [cheshire "6.2.0"]
                 [cprop "0.1.21"]
                 [diehard "0.12.1"]
                 [mount "0.1.24"]
                 ;; for Web Server & Groq Client
                 [http-kit "2.9.0-beta4"]
                 [ring/ring-core "1.15.5"]
                 [ring/ring-codec "1.3.0"]
                 #_[ring/ring-json "0.5.1"]
                 [ring-transit "0.1.6"]
                 ;; for the Frontend (ClojureScript)
                 [org.clojure/clojurescript "1.12.145"]
                 [reagent "1.3.0"]
                 [thheller/shadow-cljs "3.4.12"]
                 [com.cognitect/transit-cljs "0.8.280"]
                 [org.slf4j/slf4j-simple "2.0.13"]]
  :main ^:skip-aot fourteatoo.pathfinder.core
  :source-paths ["src/clj" "src/cljs"]
  :target-path "target/%s"
  :repl-options {:init-ns fourteatoo.pathfinder.core}
  :aliases {"slides" ["with-profile" "dev" "run" "-m" "clojure.main" "-e"
                      "(require '[slides :as s]) (s/make-slides)"]
            "notebook" ["with-profile" "dev" "run" "-m" "clojure.main" "-e"
                        "(require '[notebook :as n]) (n/make-notebook)"]
            "build-docs" ["do" ["slides"] ["notebook"]]}

  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Xmx2g"
                                  "-Dclojure.compiler.direct-linking=true"
                                  "-Djdk.attach.allowAttachSelf"]}
             :dev {:dependencies [[org.scicloj/clay "2.0.20"]
                                  [hiccup "2.0.0-RC5"]
                                  [thheller/shadow-cljs "3.4.12" :exclusions [hiccup]]]
                   :source-paths ["notebooks"]}})
