(ns rama-clojure-starter.jdbc-external-depot-v2
  (:require
   [clojure.tools.logging :as logging]
   [com.rpl.rama :refer :all]
   [com.rpl.rama.path :refer :all])
  (:import
   [com.rpl.rama.integration ExternalDepot TaskGlobalObject]
   [java.util ArrayList List]
   [java.util.concurrent CompletableFuture]))

(defn ->jdbc-depot
  [start]
  (reify
    TaskGlobalObject
    (prepareForTask [_this _task-id _context]
      (println (format "task-id: %s" _task-id)))
    ExternalDepot
    (close [_this])
    (endOffset [_this _partition-index]
      (println "endOffset: " _partition-index)
      (CompletableFuture/completedFuture (quot (System/currentTimeMillis) 1000)))
    (fetchFrom [_this _partition-index start-offset]
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
      (CompletableFuture/completedFuture start))))

(logging/warn "foo")
