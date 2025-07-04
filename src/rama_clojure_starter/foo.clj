(ns rama-clojure-starter.foo
  (:require
   [next.jdbc :as jdbc]
   [next.jdbc.connection :as connection])
  (:import
   [com.rpl.rama.integration ExternalDepot TaskGlobalObject]
   [java.util ArrayList List]
   [java.util.concurrent CompletableFuture]
   [java.util.function Supplier]
   [com.zaxxer.hikari HikariDataSource]))

(do
  (def datasource-options
    {:jdbcUrl "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1" ; DB_CLOSE_DELAY=-1 keeps it open until JVM exits
     :username "sa"
     :password ""
     :maximumPoolSize 10
     :minimumIdle 1
     :idleTimeout 600000
     :connectionTimeout 30000})
  (deftype jdbc-depot [datasource-options ^:volatile-mutable ds]
    TaskGlobalObject
    (prepareForTask [_this task-id _context]
      (when (and (= task-id 0)
                 (nil? ds))
        (println "Opening")
        (set! ds (delay (connection/->pool HikariDataSource datasource-options))))
      (println (format "task-id: %s" task-id)))
    ExternalDepot
    (close [_this]
      (when ds
        (println "Closing")
        (.close @ds)))
    (endOffset [_this _partition-index]
      (CompletableFuture/supplyAsync
       (reify Supplier
         (get [_]
           (-> (jdbc/execute! @ds ["SELECT 1000 as count"])
               first
               :COUNT)))))
    (fetchFrom [_this _partition-index start-offset]
      (CompletableFuture/supplyAsync
       (reify Supplier
         (get [_]
           (let [xs (for [row (jdbc/execute! @ds ["SELECT * from system_range(?, 10)" start-offset])]
                      (:SYSTEM_RANGE/X row))]
             (ArrayList. ^List xs))))))
    (fetchFrom [_this _partition-index start-offset end-offset]
      (CompletableFuture/supplyAsync
       (reify Supplier
         (get [_]
           (let [xs (for [row (jdbc/execute! @ds ["SELECT * from system_range(?, ?)" start-offset end-offset])]
                      (:SYSTEM_RANGE/X row))]
             (ArrayList. ^List xs))))))
    (getNumPartitions [_this]
      (CompletableFuture/completedFuture 1))
    (offsetAfterTimestampMillis [_this _partition-index _millis]
      (throw (UnsupportedOperationException. "Unimplemented method 'offsetAfterTimestampMillis'")))
    (startOffset [_this _parittion-index]
      (CompletableFuture/completedFuture 0)))
  (let [depot (->jdbc-depot datasource-options nil)]
    (.prepareForTask depot 0 nil)
    (.prepareForTask depot 1 nil)
    (println (.get (.endOffset depot 0)))
    (println (.get (.fetchFrom depot 0 0)))
    (.close depot)))
