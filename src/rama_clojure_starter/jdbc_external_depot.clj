(ns rama-clojure-starter.jdbc-external-depot
  (:require
   [com.rpl.rama.path :refer :all]
   [com.rpl.rama :refer :all]
   [clojure.string :as str]
   [com.rpl.rama.test :as rtest]
   [com.rpl.rama.aggs :as aggs]
   [com.rpl.rama.ops :as ops])
  (:import
   [com.rpl.rama.integration ExternalDepot TaskGlobalObject]
   [java.util.concurrent CompletableFuture]
   [java.util List ArrayList]))

(defn ->jdbc-depot
  [start]
  (reify
    TaskGlobalObject
    (prepareForTask [_this _task-id _context])
    ExternalDepot
    (close [_this])
    (endOffset [_this _partition-index]
      (println "endOffset")
      (CompletableFuture/completedFuture (quot (System/currentTimeMillis) 1000)))
    (fetchFrom [_this _partition-index start-offset]
      (let [end (quot (System/currentTimeMillis) 1000)
            res (ArrayList. ^List (range start-offset end))]
        (CompletableFuture/completedFuture res)))
    (fetchFrom [_this _partition-index start-offset end-offset]
      (let [res (ArrayList. ^List (range start-offset end-offset))]
        (CompletableFuture/completedFuture res)))
    (getNumPartitions [_this]
      (CompletableFuture/completedFuture 1))
    (offsetAfterTimestampMillis [_this _partition-index _millis]
      (throw (UnsupportedOperationException. "Unimplemented method 'offsetAfterTimestampMillis'")))
    (startOffset [_this _parittion-index]
      (CompletableFuture/completedFuture start))))

(defmodule WordCountModule [setup topologies]
  (declare-object setup *jdbc-depot (->jdbc-depot (quot (System/currentTimeMillis) 1000)))
  (let [s (stream-topology topologies "print-long")]
    (<<sources s
               (source> *jdbc-depot :> *long)
               (println *long))))

(comment
  (def ipc (rtest/create-ipc))

  (rtest/launch-module! ipc WordCountModule {:tasks 4 :threads 2})

  (def sentences-depot (foreign-depot ipc (get-module-name WordCountModule) "*sentences-depot"))
  (def word-counts (foreign-pstate ipc (get-module-name WordCountModule) "$$word-counts"))

  (foreign-append! sentences-depot "Hello world")
  (foreign-append! sentences-depot "hello hello goodbye")
  (foreign-append! sentences-depot "Alice says hello")

  (foreign-select-one (keypath "hello") word-counts)
  (foreign-select-one (keypath "goodbye") word-counts))

(comment
  (def manager (open-cluster-manager))

  (def sentences-depot (foreign-depot manager (get-module-name WordCountModule) "*sentences-depot"))
  (def word-counts (foreign-pstate manager (get-module-name WordCountModule) "$$word-counts"))

  (foreign-append! sentences-depot "Hello world")
  (foreign-append! sentences-depot "hello hello goodbye")
  (foreign-append! sentences-depot "Alice says hello")

  (foreign-select-one (keypath "hello") word-counts)
  (foreign-select-one (keypath "goodbye") word-counts)

  (slurp (io/resource "rama.yaml")))
