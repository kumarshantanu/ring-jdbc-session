(defproject ring-jdbc-session "0.1.0"
  :description "JDBC based HTTP session store for Ring (Clojure)"
  :url "https://github.com/kumarshantanu/ring-jdbc-session"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :warn-on-reflection true
  :profiles {:dev {:dependencies [[ring/ring-core      "1.1.8"]
                                  [clj-dbcp            "0.8.0"]
                                  [com.h2database/h2   "1.3.170"]
                                  [mysql/mysql-connector-java "5.1.6"]
                                  [postgresql/postgresql      "9.1-901.jdbc4"]]}
             :1.3 {:dependencies [[org.clojure/clojure "1.3.0"]]}
             :1.4 {:dependencies [[org.clojure/clojure "1.4.0"]]}
             :1.5 {:dependencies [[org.clojure/clojure "1.5.0-RC3"]]}}
  :aliases {"all" ["with-profile" "1.3,dev:1.4,dev:1.5,dev"]
            "dev" ["with-profile" "1.4,dev"]})
