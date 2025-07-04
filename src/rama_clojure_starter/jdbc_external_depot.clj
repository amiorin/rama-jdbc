(ns rama-clojure-starter.jdbc-external-depot
  (:require
   [clojure.tools.logging :as log]
   [com.rpl.rama :refer :all]
   [com.rpl.rama.path :refer :all]
   [com.rpl.rama.test :as rtest])
  (:import
   [com.rpl.rama.integration ExternalDepot TaskGlobalObject]
   [java.util ArrayList List]
   [java.util.concurrent CompletableFuture]))

(defn ->jdbc-depot
  [start]
  (reify
    TaskGlobalObject
    (prepareForTask [_this _task-id _context]
      (log/info (format "task-id: %s" _task-id)))
    ExternalDepot
    (close [_this])
    (endOffset [_this _partition-index]
      (log/info "endOffset: " _partition-index)
      (CompletableFuture/completedFuture (quot (System/currentTimeMillis) 1000)))
    (fetchFrom [_this _partition-index start-offset]
      (log/info (format "fetchFrom: %s, start-offset: %s" _partition-index start-offset))
      (let [end (quot (System/currentTimeMillis) 1000)
            res (ArrayList. ^List (range start-offset end))]
        (CompletableFuture/completedFuture res)))
    (fetchFrom [_this _partition-index start-offset end-offset]
      (log/info (format "start-offset: %s, end-offset %s" start-offset end-offset))
      (let [res (ArrayList. ^List (range start-offset end-offset))]
        (CompletableFuture/completedFuture res)))
    (getNumPartitions [_this]
      (log/info "getNumPartitions")
      (CompletableFuture/completedFuture 1))
    (offsetAfterTimestampMillis [_this _partition-index _millis]
      (throw (UnsupportedOperationException. "Unimplemented method 'offsetAfterTimestampMillis'")))
    (startOffset [_this _parittion-index]
      (log/info "startOffset")
      (CompletableFuture/completedFuture start))))

(defmodule WordCountModule [setup topologies]
  (declare-object setup *jdbc-depot (->jdbc-depot (quot (System/currentTimeMillis) 1000)))
  (let [s (stream-topology topologies "print-long")]
    (<<sources s
               (source> *jdbc-depot :> *long)
               (anchor> <default-root>)
               (println *long)
               (hook> <default-root>)
               (println *long))))

(def ipc (rtest/create-ipc))

(rtest/launch-module! ipc WordCountModule {:tasks 4 :threads 2})

(rtest/destroy-module! ipc "rama-clojure-starter.jdbc-external-depot/WordCountModule")

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
