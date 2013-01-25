(ns ring-jdbc-session.core-test
  (:require [clojure.pprint :as pp]
            [clj-dbcp.core  :as dbcp]
            [ring-jdbc-session.core :as rjss])
  (:use clojure.test)
  (:import (java.sql ResultSet)))


(def ds nil)


(def opts {})


(defmacro with-stmt
  [bindings & body]
  `(rjss/with-connection (rjss/make-conn-factory ds)
     (rjss/do-prepared ~bindings
                       ~@body)))


(defmacro with-session-table
  [& body]
  `(do
     ;; setup
     (rjss/create-session-table ds opts)
     (try ~@body
       (finally
         ;; teardown
         (rjss/drop-session-table ds opts)))))


(defn print-db-table [table]
  (with-stmt [stmt (str "SELECT * FROM " table)]
    (with-open [^ResultSet rs (.executeQuery stmt)]
      ;;(is (not (.next r)))
      (pp/print-table (resultset-seq rs)))))


(defn count-rows [table]
  (with-stmt [stmt (str "SELECT COUNT(*) AS cnt FROM " table)]
    (with-open [^ResultSet rs (.executeQuery stmt)]
      (.next rs)
      (.getLong rs "cnt"))))


(defn sleep-seconds
  [n]
  (print (format "Sleeping for %d seconds..." n))
  (flush)
  (rjss/sleep (* 1000 n))
  (println "woke up."))


(deftest table-management-works
  (testing "Create session table"
           (rjss/create-session-table ds opts))
  (testing "Read from created session table"
           (with-stmt [stmt "SELECT * FROM ring_session"]
             (.executeQuery stmt)))
  (testing "Drop session table"
           (rjss/drop-session-table ds opts))
  (testing "Read from dropped session table should throw SQLException"
           (is (thrown? java.sql.SQLException
                        (with-stmt [stmt "SELECT * FROM ring_session"]
                          (.executeQuery stmt))))))


(deftest session-store-works
  (with-session-table
    (let [store (rjss/make-session-store ds (merge {:expire-secs 1} opts))]
      (testing "SQL COUNT operation"
               (with-stmt [stmt "SELECT COUNT(*) AS cnt FROM ring_session"]
                 (with-open [^ResultSet rs (.executeQuery stmt)]
                   (is (.next rs))
                   (is (= 0 (.getLong rs "cnt")) "count=0 before tests"))))
      (testing "Write (create) new session key/val"
               (let [struct {:foo "foo" :bar [2 :baz]}]
                 (is (string? (rjss/write store nil "init-value")))
                 (is (= "foo" (rjss/write store "foo" "foo-value")))
                 (is (= "baz" (rjss/write store "baz" struct)))
                 (is (= "foo-value" (rjss/read store "foo")) "create")
                 (is (= struct      (rjss/read store "baz")) "data structure")))
      (testing "Read/write (update) old session key/val"
               (rjss/write store "bar" "bar-value")
               (rjss/write store "bar" "bar-bar")
               (is (= "bar-bar" (rjss/read store "bar")) "update"))
      (testing "Read non-existing session val"
               (is (= nil (rjss/read store "quux")) "non-existing key"))
      (testing "Delete session key/val"
               (rjss/delete store "foo")
               (is (= nil (rjss/read store "foo")) "deleted key"))
      (testing "Read expired session val"
               (sleep-seconds 2)
               (is (= nil (rjss/read store "bar")) "expired key is not retrievable")
               (with-stmt [stmt "SELECT 1 AS present FROM ring_session WHERE session_key=?"]
                 (.setString stmt 1 "bar")
                 (with-open [^ResultSet rs (.executeQuery stmt)]
                   (is (.next rs) "expired key is not deleted"))))
      (testing "Clean stale keys"
               ;; (println "Session table before cleanup") (print-db-table "ring_session")
               (is (= 3 (count-rows "ring_session")) "count=3 before cleanup")
               (rjss/clean store)
               ;; (println "Session table after cleanup") (print-db-table "ring_session")
               (is (= 0 (count-rows "ring_session")) "count=2 after cleanup")))))


(deftest session-cleaning-works
  (with-session-table
    (let [store (rjss/make-session-store ds (merge {:expire-secs 1} opts))]
      (testing "Cleaner"
               (is (= "foo" (rjss/write store "foo" "foo-value")))
               (is (= "bar" (rjss/write store "bar" {:foo 'bar})))
               (is (= 2 (count-rows "ring_session")) "count=2 before cleanup")
               (let [cleaner (rjss/start-cleaner store {:interval-secs 2})]
                 (sleep-seconds 3)
                 (is (= 0 (count-rows "ring_session")) "count=0 after cleanup")
                 (rjss/stop cleaner)
                 (is (true? (.stopped? cleaner)) "Cleaner should be stopped"))
               (is (= "foo" (rjss/write store "foo" "foo-value")))
               (is (= "bar" (rjss/write store "bar" {:foo 'bar})))
               (sleep-seconds 3)
               (is (= 2 (count-rows "ring_session")) "count=2 before cleanup")))))


(deftest non-expiring-session-store-works
  (with-session-table
    (let [store (rjss/make-session-store ds (merge {:expire-secs 0} opts))]
      (testing "Non-expiring keys"
               (is (= "foo" (rjss/write store "foo" "foo-value")))
               (is (= "bar" (rjss/write store "bar" {:foo 'bar})))
               (sleep-seconds 2)
               (is (= 2 (count-rows "ring_session")) "count=2 after sleep")
               (is (= "foo-value" (rjss/read store "foo")))
               (is (= {:foo 'bar} (rjss/read store "bar"))))
      (testing "Cleaner for non-expiring store"
               (let [cleaner (rjss/start-cleaner store {:interval-secs 2})]
                 (sleep-seconds 3)
                 (is (= 2 (count-rows "ring_session")) "count=2 after sleep")
                 (is (= "foo-value" (rjss/read store "foo")))
                 (is (= {:foo 'bar} (rjss/read store "bar")))
                 (rjss/stop cleaner)
                 (is (true? (.stopped? cleaner)) "Cleaner should be stopped"))))))


(defn test-ns-hook []
  (doseq [each (read-string (slurp "test-config/config.clj"))]
    (with-redefs [opts (first each)
                  ds   (apply dbcp/make-datasource (rest each))]
      (println "Running tests using config:" each)
      (table-management-works)
      (session-store-works)
      (session-cleaning-works)
      (non-expiring-session-store-works))))
