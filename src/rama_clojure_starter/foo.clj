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
        (set! ds (delay (connection/->pool HikariDataSource datasource-options))))
      (println (format "task-id: %s" task-id)))
    ExternalDepot
    (close [_this]
      (when ds
        (.close @ds)))
    (endOffset [_this _partition-index]
      (CompletableFuture/supplyAsync
       (reify Supplier
         (get [_]
           (quot (System/currentTimeMillis) 1000)))))
    (fetchFrom [_this _partition-index start-offset]
      (jdbc/execute! @ds ["SELECT 1"])
      (println (format "fetchFrom: %s, start-offset: %s" _partition-index start-offset))
      (let [end (quot (System/currentTimeMillis) 1000)
            res (ArrayList. ^List (range start-offset end))]
        (CompletableFuture/completedFuture res)))
    (fetchFrom [_this _partition-index start-offset end-offset]
      (println (format "start-offset: %s, end-offset %s" start-offset end-offset))
      (let [res (ArrayList. ^List (range start-offset end-offset))]
        (CompletableFuture/completedFuture res)))
    (getNumPartitions [_this]
      (println "getNumPartitions")
      (CompletableFuture/completedFuture 1))
    (offsetAfterTimestampMillis [_this _partition-index _millis]
      (throw (UnsupportedOperationException. "Unimplemented method 'offsetAfterTimestampMillis'")))
    (startOffset [_this _parittion-index]
      (println "startOffset")
      (CompletableFuture/completedFuture (quot (System/currentTimeMillis) 1000))))
  (let [depot (->jdbc-depot datasource-options nil)]
    (.prepareForTask depot 0 nil)
    (.close depot)))
