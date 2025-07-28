(ns rama-jdbc.core
  (:require
   [clojure.tools.logging :as log]
   [com.rpl.rama :refer :all]
   [com.rpl.rama.path :refer :all]
   [com.rpl.specter :as s]
   [hugsql.adapter.next-jdbc :as next-adapter]
   [hugsql.core :as h]
   [next.jdbc.connection :as connection]
   [next.jdbc.result-set :as result-set]
   [taoensso.nippy :as nippy])
  (:import
   [com.rpl.rama.integration ExternalDepot TaskGlobalObject]
   [com.zaxxer.hikari HikariDataSource]
   [java.util ArrayList List]
   [java.util.concurrent CompletableFuture]
   [java.util.function Supplier]))

(h/set-adapter! (next-adapter/hugsql-adapter-next-jdbc {:builder-fn result-set/as-unqualified-maps}))

(defn pad-to-size [s target-size padding-val]
  (assert (nat-int? target-size) "The batch size must be 0 or greater")
  (let [current-size (count s)]
    (if (>= current-size target-size)
      (take target-size s) ;; If already target size or larger, truncate
      (take target-size (concat s (repeat padding-val))))))

(defprotocol SqlOps
  (get-max-offset-id [this ds])
  (get-min-offset-id [this ds])
  (get-records [this ds opts]))

(defrecord dispatcher [m]
  SqlOps
  (get-max-offset-id [this ds]
    ((get-in this [:m :get-max-offset-id :fn]) ds))
  (get-min-offset-id [this ds]
    ((get-in this [:m :get-min-offset-id :fn]) ds))
  (get-records [this ds opts]
    ((get-in this [:m :get-records :fn]) ds opts)))

(defn do-get-records [dispatcher ds {:keys [start-offset end-offset]}]
  (let [pg-end-offset (-> (get-max-offset-id dispatcher ds)
                          :max_offset_id)
        batch-size (- (min end-offset pg-end-offset) start-offset)]
    (as-> (get-records dispatcher ds {:start-offset start-offset
                                      :end-offset end-offset}) $
      (s/transform [s/ALL s/MAP-VALS #(instance? java.sql.Array %)] #(into [] (.getArray %)) $)
      (pad-to-size $ batch-size nil)
      (ArrayList. ^List $))))

(deftype jdbc-depot [datasource-options
                     depot-options
                     ^:volatile-mutable ds
                     ^:volatile-mutable dispatcher]
  TaskGlobalObject
  (prepareForTask [_this task-id _context]
    (let [file (or (:file depot-options)
                   (throw (ex-info ":file is required" {})))]
      (when (and (= task-id 0)
                 (nil? ds))
        (set! ds (delay (connection/->pool HikariDataSource datasource-options)))
        (set! dispatcher (->dispatcher (h/map-of-db-fns file)))))
    (log/infof "prepareForTask with task-id: %s" task-id))
  ExternalDepot
  (getNumPartitions [_this]
    (log/info "getNumPartitions -> 1")
    (CompletableFuture/completedFuture 1))
  (offsetAfterTimestampMillis [_this _partition-index _millis]
    (throw (UnsupportedOperationException. "Unimplemented method 'offsetAfterTimestampMillis'")))
  (startOffset [_this _partition-index]
    (throw (UnsupportedOperationException. "Unimplemented method 'offsetAfterTimestampMillis'")))
  (endOffset [_this partition-index]
    (CompletableFuture/supplyAsync
     (reify Supplier
       (get [_]
         (let [res (-> (get-max-offset-id dispatcher @ds)
                       :max_offset_id)]
           (log/infof "endOffset with partition-index: %s -> %s" partition-index res)
           res)))))
  (fetchFrom [_this partition-index start-offset]
    (CompletableFuture/supplyAsync
     (reify Supplier
       (get [_]
         (let [limit (or (:limit depot-options)
                         (throw (ex-info ":limit is required" {})))
               records (do-get-records dispatcher @ds {:start-offset start-offset
                                                       :end-offset (+ start-offset limit)})]
           (log/infof "fetchFrom with partition-index: %s, start-offset: %s limit: %s -> %s" partition-index start-offset limit (count records))
           records)))))
  (fetchFrom [_this partition-index start-offset end-offset]
    (CompletableFuture/supplyAsync
     (reify Supplier
       (get [_]
         (let [records (do-get-records dispatcher @ds {:start-offset start-offset
                                                       :end-offset end-offset})]
           (log/infof "fetchFrom with partition-index: %s, start-offset: %s, end-offset: %s -> %s" partition-index start-offset end-offset (count records))
           records)))))
  (close [_this]
    (if ds
      (do
        (log/info "Closing with ds defined")
        (.close @ds))
      (log/info "Closing with ds nil"))))

(nippy/extend-freeze
 jdbc-depot
 ::jdbc-depot
 [j data-output]
 (nippy/freeze-to-out! data-output (.datasource-options j))
 (nippy/freeze-to-out! data-output (.depot-options j)))

(nippy/extend-thaw
 ::jdbc-depot
 [data-input]
 (jdbc-depot. (nippy/thaw-from-in! data-input)
              (nippy/thaw-from-in! data-input)
              nil
              nil))

(comment)
