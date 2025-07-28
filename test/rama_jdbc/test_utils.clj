(ns rama-jdbc.test-utils
  (:require
   [aero.core :as aero]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [integrant.repl.state :as state]
   [next.jdbc :as jdbc]
   [next.jdbc.connection :as connection]
   [rama-jdbc.ig-keys])
  (:import
   [com.zaxxer.hikari HikariDataSource]
   [java.time ZonedDateTime]))

(defmethod aero/reader 'ig/ref
  [_ _ value]
  (ig/ref value))

(defmethod aero/reader 'ig/refset
  [_ _ value]
  (ig/refset value))

(defn read-config
  [filename options]
  (log/info "Reading config" filename)
  (aero/read-config (io/resource filename) options))

(def ^:const system-filename "system.edn")

(defn system-config
  [options]
  (read-config system-filename options))

(defonce system (atom nil))

(defn system-state []
  (or @system state/system))

(defn system-fixture []
  (fn [f]
    (when (nil? (system-state))
      (reset! system (-> {:profile :test}
                         (system-config)
                         (ig/init))))
    (f)
    (some-> (deref system) (ig/halt!))
    (reset! system nil)))

(defn clj->pg [conn m]
  (reduce (fn [res [k v]]
            (assoc res k (cond (and (vector? v) (every? integer? v))
                               (.createArrayOf conn "integer" (to-array v))
                               (and (vector? v) (every? string? v))
                               (.createArrayOf conn "text" (to-array v))
                               (instance? ZonedDateTime v)
                               (.toOffsetDateTime v)
                               :else v)))
          {}
          m))

(comment
  (let [jdbc-url (-> (system-state)
                     :postgres/server
                     :jdbc-url)
        datasource-options {:jdbcUrl jdbc-url
                            :maximumPoolSize 10
                            :minimumIdle 1
                            :idleTimeout 600000
                            :connectionTimeout 30000}
        ds (connection/->pool HikariDataSource datasource-options)]
    (with-open [conn (jdbc/get-connection ds)]
      (clj->pg conn {:teams (vec (range 10))}))))
