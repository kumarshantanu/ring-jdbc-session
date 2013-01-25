(ns ring-jdbc-session.core
  "JDBC based session store"
  (:refer-clojure :exclude [read])
  (:require [ring.middleware.session.store :as rss])
  (:import java.util.UUID
           (java.sql  Connection PreparedStatement ResultSet Timestamp)
           (javax.sql DataSource)))


(defn new-session-key [prefix]
  (str prefix ":" (str (UUID/randomUUID))))


(defn make-conn-factory [ds-or-cf]
  (cond
    (fn? ds-or-cf) ds-or-cf
    (instance? DataSource ds-or-cf) #(.getConnection ^DataSource ds-or-cf)
    :otherwise (throw (IllegalArgumentException.
                        (str "Expected javax.sql.DataSource or function, found: "
                             (class ds-or-cf) " " ds-or-cf)))))


(def ^{:dynamic true :tag Connection} *connection* nil)


(defmacro with-connection [conn-factory & body]
  (let [conn (gensym)]
    `(with-open [~(with-meta conn {:tag Connection}) (~conn-factory)]
       (binding [*connection* ~conn]
         ~@body))))


(defmacro do-prepared [bindings & body]
  (let [pairs (->> (partition 2 bindings)
                (mapcat (fn [[stmt-var sql-template]]
                          [`~(with-meta stmt-var {:tag PreparedStatement})
                           `(.prepareStatement *connection* ~sql-template)])))]
    `(with-open ~(vec pairs)
       ~@body)))


(defn ^Timestamp ts-now
  ([]
    (ts-now 0))
  ([delta]
    (Timestamp. (+ (System/currentTimeMillis) delta))))


(defn make-ts-fn
  [expire-millis]
  (if (pos? expire-millis)
    #(ts-now (- expire-millis))
    (constantly (Timestamp. 0))))


(defn make-retriever [table-name key-column val-column ts-column expire-millis]
  (let [sql-select (format "SELECT %s FROM %s WHERE %s=? AND %s>=?"
                           val-column table-name key-column ts-column)
        sql-update (format "UPDATE %s SET %s=? WHERE %s=?"
                           table-name ts-column key-column)
        ts-fn      (make-ts-fn expire-millis)]
    (fn [session-key] {:pre [(string? session-key)]}
      (do-prepared [select-stmt sql-select
                    update-stmt sql-update]
                   (doto select-stmt
                     (.setString    1 session-key)
                     (.setTimestamp 2 (ts-fn)))
                   (with-open [^ResultSet rs (.executeQuery select-stmt)]
                     (when (.next rs)
                       (let [r (.getObject rs 1)]
                         ;; update last-accessed timestamp
                         (doto update-stmt
                           (.setTimestamp 1 (ts-now))
                           (.setString    2 session-key)
                           (.executeUpdate))
                         r)))))))


(defn make-persister [table-name key-column val-column ts-column]
  (let [sql-update (format "UPDATE %s SET %s=?, %s=? WHERE %s=?"
                           table-name val-column ts-column key-column)
        sql-insert (format "INSERT INTO %s (%s, %s, %s) VALUES (?, ?, ?)"
                           table-name key-column val-column ts-column)]
    (fn [session-key session-val] {:pre [(string? session-key)]}
      (do-prepared [update-stmt sql-update
                    insert-stmt sql-insert]
                   (doto update-stmt ; update successful?
                     (.setObject    1 session-val)
                     (.setTimestamp 2 (ts-now))
                     (.setString    3 session-key))
                   (when (zero? (.executeUpdate update-stmt))
                     ;; insert
                     (doto insert-stmt
                       (.setString    1 session-key)
                       (.setObject    2 session-val)
                       (.setTimestamp 3 (ts-now))
                       (.executeUpdate))))
      nil)))


(defn make-remover [table-name key-column]
  (let [sql (format "DELETE FROM %s WHERE %s=?" table-name key-column)]
    (fn [session-key] {:pre [(string? session-key)]}
      (do-prepared [stmt sql]
                   (doto stmt
                     (.setString 1 session-key)
                     (.executeUpdate)))
      nil)))


(defn make-cleaner [table-name ts-column expire-millis]
  (let [sql   (format "DELETE FROM %s WHERE %s < ?" table-name ts-column)
        ts-fn (make-ts-fn expire-millis)]
    (fn []
      (do-prepared [stmt sql]
                   (doto stmt
                     (.setTimestamp 1 (ts-fn))
                     (.executeUpdate)))
      nil)))


(defprotocol SessionStoreCleanup
  "Cleanup API for session store"
  (clean-session [_] "Clean up the expired key-val pairs"))


(deftype JdbcStore [conn-factory retriever persister remover cleaner
                    serializer deserializer prefix]
  rss/SessionStore
  (read-session [_ session-key]
    (when session-key  ; read from the DB only when session-key is not nil
      (with-connection conn-factory
        (when-let [val-payload (retriever session-key)]
          (deserializer val-payload)))))
  (write-session [_ session-key data]
    (with-connection conn-factory
      (let [session-key (or session-key (new-session-key prefix))
            val-payload (serializer data)]
        (persister session-key val-payload)
        session-key)))
  (delete-session [_ session-key]
    (with-connection conn-factory
      (remover session-key)))
  SessionStoreCleanup
  (clean-session [_]
    (with-connection conn-factory
      (cleaner))))


;; ========== session store ==========


(defn make-session-store
  "Create a JDBC-backed session storage engine. Argument `conn-factory` can be
  either a javax.sql.DataSource object or a no-arg function that returns a
  java.sql.Connection object."
  ([conn-factory]
    (make-session-store conn-factory {}))
  ([conn-factory {:keys [table-name key-column val-column ts-column
                         serializer deserializer prefix expire-secs]
                  :or {table-name   "ring_session"
                       key-column   "session_key"
                       val-column   "session_val"
                       ts-column    "session_ts"
                       serializer   pr-str
                       deserializer read-string
                       prefix       "session"
                       expire-secs  1800}
                  :as opts}] {:pre [(or (instance? DataSource conn-factory)
                                        (fn? conn-factory))
                                    (string? table-name)
                                    (string? key-column)
                                    (string? val-column)
                                    (string? ts-column)
                                    (fn? serializer)
                                    (fn? deserializer)
                                    (string? prefix)
                                    (number? expire-secs)]}
    (let [retriever (make-retriever table-name key-column val-column ts-column
                                    (* 1000 expire-secs))
          persister (make-persister table-name key-column val-column ts-column)
          remover   (make-remover   table-name key-column)
          cleaner   (make-cleaner   table-name ts-column (* 1000 expire-secs))]
      (JdbcStore. (make-conn-factory conn-factory)
                  retriever persister remover cleaner
                  serializer deserializer prefix ))))


(defn read [session-store session-key] {:pre [(instance? JdbcStore session-store)
                                              (string? session-key)]}
  (.read-session ^JdbcStore session-store session-key))


(defn write [session-store session-key session-val] {:pre [(or (string? session-key)
                                                               (nil? session-key))
                                                           (instance? JdbcStore session-store)]}
  (.write-session ^JdbcStore session-store session-key session-val))


(defn delete [session-store session-key] {:pre [(instance? JdbcStore session-store)
                                                (string? session-key)]}
  (.delete-session ^JdbcStore session-store session-key))


(defn clean [session-store] {:pre [(instance? JdbcStore session-store)]}
  (.clean-session ^JdbcStore session-store))


;; ========== starting/stopping the cleaner ==========


(defprotocol Stoppable
  "Something that can be stopped"
  (stopped? [_] "Return true if stopped, false otherwise")
  (stop     [_] "Stop (idempotent)"))


(defn sleep [millis]
  (let [timeout (+ millis (System/currentTimeMillis))]
    (while (< (System/currentTimeMillis) timeout)
      (try (Thread/sleep (- timeout (System/currentTimeMillis)))
        (catch InterruptedException _
          (.interrupt ^Thread (Thread/currentThread)))))))


(defn start-cleaner
  ([^JdbcStore session-store]
    (start-cleaner session-store {}))
  ([^JdbcStore session-store {:keys [interval-secs]
                              :or {interval-secs 60}
                              :as opts}]
    (let [state-atom  (atom :running)
          store-agent (agent session-store)
          interval-ms (* 1000 interval-secs)
          runner-fn   (fn runner [_]
                        (try
                          (when @state-atom
                            (try
                              (.clean-session session-store)
                              (catch Exception e
                                (.printStackTrace e)))
                            (sleep interval-ms)
                            (send-off store-agent runner))
                          (catch Throwable t
                            (println "Exiting, error occured:" (.getMessage t))
                            (.printStackTrace t))))]
      (send-off store-agent runner-fn)
      (reify Stoppable
        (stopped? [_] (not @state-atom))
        (stop     [_] (swap! state-atom (constantly false)))))))


(defn stop-cleaner [session-cleaner] {:pre [(satisfies? Stoppable session-cleaner)]}
  (.stop session-cleaner))


;; ========== table create/drop stuff ==========


(defn create-session-table
  ([conn-factory]
    (create-session-table conn-factory {}))
  ([conn-factory {:keys [table-name key-column val-column ts-column
                         key-column-type val-column-type ts-column-type]
                  :or {table-name  "ring_session"
                       key-column  "session_key"
                       val-column  "session_val"
                       ts-column   "session_ts"
                       key-column-type "VARCHAR(100) UNIQUE NOT NULL"
                       val-column-type "VARCHAR(1024)"
                       ts-column-type  "TIMESTAMP NOT NULL"}
                  :as opts}]
    (with-connection (make-conn-factory conn-factory)
      (do-prepared [stmt (str "CREATE TABLE " table-name " ("
                              key-column " " key-column-type ", "
                              val-column " " val-column-type ", "
                              ts-column  " " ts-column-type ")")]
                   (.executeUpdate stmt)))))


(defn drop-session-table
  ([conn-factory]
    (drop-session-table conn-factory {}))
  ([conn-factory {:keys [table-name]
                  :or {table-name "ring_session"}
                  :as opts}]
    (with-connection (make-conn-factory conn-factory)
      (do-prepared [stmt (str "DROP TABLE " table-name)]
                   (.executeUpdate stmt)))))
