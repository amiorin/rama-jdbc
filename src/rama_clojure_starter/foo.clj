(ns rama-clojure-starter.foo
  (:require
   [clojure.tools.logging :as log]
   [com.rpl.rama :refer :all]
   [com.rpl.rama.path :refer :all]
   [com.rpl.rama.test :as rtest]
   [next.jdbc :as jdbc]
   [next.jdbc.connection :as connection]
   [taoensso.nippy :as nippy])
  (:import
   [com.rpl.rama.integration ExternalDepot TaskGlobalObject]
   [com.zaxxer.hikari HikariDataSource]
   [java.util ArrayList List]
   [java.util.concurrent CompletableFuture]
   [java.util.function Supplier]))

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
    (log/info "endOffset: " _partition-index)
    (CompletableFuture/supplyAsync
     (reify Supplier
       (get [_]
         (-> (jdbc/execute! @ds ["SELECT 1000 as count"])
             first
             :COUNT)
         0))))
  (fetchFrom [_this _partition-index start-offset]
    (log/info (format "fetchFrom: %s, start-offset: %s" _partition-index start-offset))
    (CompletableFuture/supplyAsync
     (reify Supplier
       (get [_]
         (let [xs (for [row (jdbc/execute! @ds ["SELECT * from system_range(?, ?)" start-offset (+ start-offset 10)])]
                    (:SYSTEM_RANGE/X row))
               res (ArrayList. ^List xs)]
           (Thread/sleep 1000)
           res)))))
  (fetchFrom [_this _partition-index start-offset end-offset]
    (log/info (format "start-offset: %s, end-offset %s" start-offset end-offset))
    (CompletableFuture/supplyAsync
     (reify Supplier
       (get [_]
         (let [xs (for [row (jdbc/execute! @ds ["SELECT * from system_range(?, ?)" start-offset end-offset])]
                    (:SYSTEM_RANGE/X row))]
           (ArrayList. ^List xs))))))
  (getNumPartitions [_this]
    (log/info "getNumPartitions")
    (CompletableFuture/completedFuture 1))
  (offsetAfterTimestampMillis [_this _partition-index _millis]
    (throw (UnsupportedOperationException. "Unimplemented method 'offsetAfterTimestampMillis'")))
  (startOffset [_this _parittion-index]
    (log/info "startOffset")
    (CompletableFuture/completedFuture 0)))

(nippy/extend-freeze
 jdbc-depot
 ::jdbc-depot
 [j data-output]
 (nippy/freeze-to-out! data-output (.datasource-options j)))

(nippy/extend-thaw
 ::jdbc-depot
 [data-input]
 (jdbc-depot. (nippy/thaw-from-in! data-input) nil))

(defmodule WordCountModule [setup topologies]
  (declare-object setup *jdbc-depot (->jdbc-depot datasource-options nil))
  (let [s (stream-topology topologies "print-long")]
    (<<sources s
               (source> *jdbc-depot :> *long)
               (anchor> <default-root>)
               (println *long)
               (hook> <default-root>)
               (println *long))))

(comment
  (def ipc (rtest/create-ipc))

  (rtest/launch-module! ipc WordCountModule {:tasks 4 :threads 2})

  (rtest/destroy-module! ipc "rama-clojure-starter.foo/WordCountModule"))

(def ipc (rtest/create-ipc))

(rtest/launch-module! ipc WordCountModule {:tasks 4 :threads 2})
