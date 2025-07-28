(ns rama-jdbc.core-test
  (:require
   [clojure.pprint :as pp]
   [clojure.test :refer [deftest is testing use-fixtures]] ;; [com.rpl.rama :refer :all]
   [com.rpl.rama :refer :all]
   [com.rpl.rama.path :refer :all]
   [com.rpl.rama.test :as rtest]
   [com.rpl.specter :as s]
   [hugsql.adapter.next-jdbc :as next-adapter]
   [hugsql.core :as h]
   [next.jdbc :as jdbc]
   [next.jdbc.connection :as connection]
   [next.jdbc.result-set :as result-set]
   [rama-jdbc.core :refer [->dispatcher ->jdbc-depot do-get-records
                           get-max-offset-id get-min-offset-id]]
   [rama-jdbc.test-utils :refer [clj->pg system-fixture system-state]])
  (:import
   [com.zaxxer.hikari HikariDataSource]
   [java.util.concurrent ExecutionException]))

(when (nil? (system-state))
  (use-fixtures :each (system-fixture)))

(h/set-adapter! (next-adapter/hugsql-adapter-next-jdbc {:builder-fn result-set/as-unqualified-maps}))

(def m (h/map-of-db-fns "sql/queries.sql"))

(deftest smoke-test-integrant
  (testing "loading of the system"
    (is (#{:test :dev} (:system/env (system-state))))))

(defn fixtures [jdbc-url start end]
  (let [datasource-options {:jdbcUrl jdbc-url
                            :maximumPoolSize 10
                            :minimumIdle 1
                            :idleTimeout 600000
                            :connectionTimeout 30000}]
    (with-open [ds (connection/->pool HikariDataSource datasource-options)]
      ((get-in m [:delete-all-users-records! :fn]) ds)
      (doseq [offset-id (range start end)]
        ((get-in m [:insert-user-record! :fn]) ds {:offset-id offset-id
                                                   :new-id (random-uuid)
                                                   :old-id nil
                                                   :operation-type "I"})))))

(deftest smoke-test-rama
  (testing "loading of the rama module"
    (let [jdbc-url (-> (system-state)
                       :postgres/server
                       :jdbc-url)
          _ (fixtures jdbc-url 1 2)
          datasource-options {:jdbcUrl jdbc-url
                              :maximumPoolSize 10
                              :minimumIdle 1
                              :idleTimeout 600000
                              :connectionTimeout 30000}
          depot-options {:limit 100
                         :file "sql/queries.sql"}
          module (module [setup topologies]
                         (declare-object setup *jdbc-depot (->jdbc-depot
                                                            datasource-options
                                                            depot-options
                                                            nil
                                                            nil))
                         (let [s (stream-topology topologies "print-records")]
                           (<<sources s
                                      (source> *jdbc-depot :> *data)
                                      (anchor> <default-root>)
                                      (pp/pprint (into (sorted-map) *data))
                                      (hook> <default-root>)
                                      (pp/pprint (into (sorted-map) *data)))))]
      (with-open [ipc (rtest/create-ipc)]
        (rtest/launch-module! ipc module {:tasks 4 :threads 2})
        (Thread/sleep 10000)
        (rtest/destroy-module! ipc (get-module-name module)))
      (is true))))

(defn resolve-cf [f]
  (try
    (.get f)
    (catch ExecutionException e
      (let [cause (.getCause e)]
        (if cause
          (.printStackTrace cause)
          (.printStackTrace e))))
    (catch InterruptedException e
      (.interrupt Thread/currentThread)
      (.printStackTrace e))))

(deftest all-cases
  (testing "that we consume always the right number of records"
    (doseq [[start-offset
             pg-start-offset
             pg-end-offset
             cnt-wo-nils
             cnt]
            [[1 50 55 5 54]
             [1 101 105 0 100]
             [1 1 2 1 1]
             [1 1 1 0 0]
             [1 1 101 100 100]
             [1 1 200 100 100]]]
      (let [jdbc-url (-> (system-state)
                         :postgres/server
                         :jdbc-url)
            datasource-options {:jdbcUrl jdbc-url
                                :maximumPoolSize 10
                                :minimumIdle 1
                                :idleTimeout 600000
                                :connectionTimeout 30000}
            batch-size 100
            depot-options {:limit batch-size
                           :file "sql/queries.sql"}
            jdbc-depot (->jdbc-depot datasource-options
                                     depot-options
                                     nil
                                     nil)
            _ (do (fixtures jdbc-url pg-start-offset pg-end-offset)
                  (.prepareForTask jdbc-depot 0 nil))
            records (-> (.fetchFrom jdbc-depot 0 start-offset)
                        resolve-cf)
            _ (.close jdbc-depot)
            cnt-1 (count (s/setval [s/ALL nil?] s/NONE records))
            cnt-2 (count records)]
        (is (= cnt-wo-nils cnt-1))
        (is (= cnt cnt-2))))))

(deftest insert-update-delete
  (testing "_records is populated correctly"
    (let [_ (h/set-adapter! (next-adapter/hugsql-adapter-next-jdbc {:builder-fn result-set/as-unqualified-maps}))
          m (h/map-of-db-fns "sql/queries.sql")
          dispatcher (->dispatcher m)
          jdbc-url (-> (system-state)
                       :postgres/server
                       :jdbc-url)
          datasource-options {:jdbcUrl jdbc-url
                              :maximumPoolSize 10
                              :minimumIdle 1
                              :idleTimeout 600000
                              :connectionTimeout 30000}
          delete-all-users-records! (get-in m [:delete-all-users-records! :fn])
          insert-user! (get-in m [:insert-user! :fn])
          update-user! (get-in m [:update-user! :fn])
          delete-user! (get-in m [:delete-user! :fn])
          ds (connection/->pool HikariDataSource datasource-options)
          insert-uuid (random-uuid)
          update-uuid (random-uuid)
          update-uuid-new (random-uuid)
          update-uuid-old (random-uuid)
          delete-uuid (random-uuid)
          friends (vec (range 10))
          _ (with-open [conn (jdbc/get-connection ds)]
              (delete-all-users-records! ds)
              (doseq [id [insert-uuid update-uuid update-uuid-old delete-uuid]]
                (insert-user! ds (clj->pg conn {:id id
                                                :user-id id
                                                :friends friends})))
              (update-user! ds (clj->pg conn {:old-id update-uuid
                                              :new-id update-uuid
                                              :user-id update-uuid
                                              :friends friends}))
              (update-user! ds (clj->pg conn {:old-id update-uuid-old
                                              :new-id update-uuid-new
                                              :user-id update-uuid-new
                                              :friends friends}))
              (delete-user! ds {:id delete-uuid}))
          min-offset-id (-> (get-min-offset-id dispatcher ds)
                            :min_offset_id)
          max-offset-id (-> (get-max-offset-id dispatcher ds)
                            :max_offset_id)
          result (->> (do-get-records dispatcher ds {:start-offset min-offset-id
                                                     :end-offset max-offset-id})
                      (map #(into (sorted-map) %))
                      (map #(select-keys % [:recs_new_id
                                            :recs_offset_id
                                            :recs_old_id
                                            :recs_operation_type
                                            :id
                                            :user_id
                                            :friends])))
          expect [{:recs_new_id insert-uuid
                   :recs_offset_id 1
                   :recs_old_id nil
                   :recs_operation_type "I"
                   :id insert-uuid
                   :user_id (str insert-uuid)
                   :friends friends}
                  {:recs_new_id update-uuid
                   :recs_offset_id 2
                   :recs_old_id nil
                   :recs_operation_type "I"
                   :id update-uuid
                   :user_id (str update-uuid)
                   :friends friends}
                  {:recs_new_id update-uuid-old
                   :recs_offset_id 3
                   :recs_old_id nil
                   :recs_operation_type "I"
                   :id nil
                   :user_id nil
                   :friends nil}
                  {:recs_new_id delete-uuid
                   :recs_offset_id 4
                   :recs_old_id nil
                   :recs_operation_type "I"
                   :id nil
                   :user_id nil
                   :friends nil}
                  {:recs_new_id update-uuid
                   :recs_offset_id 5
                   :recs_old_id update-uuid
                   :recs_operation_type "U"
                   :id update-uuid
                   :user_id (str update-uuid)
                   :friends friends}
                  {:recs_new_id update-uuid-new
                   :recs_offset_id 6
                   :recs_old_id update-uuid-old
                   :recs_operation_type "U"
                   :id update-uuid-new
                   :user_id (str update-uuid-new)
                   :friends friends}
                  {:recs_new_id nil
                   :recs_offset_id 7
                   :recs_old_id delete-uuid
                   :recs_operation_type "D"
                   :id nil
                   :user_id nil
                   :friends nil}]]
      (.close ds)
      (is (= expect result)))))

(comment
  (let [_ (h/set-adapter! (next-adapter/hugsql-adapter-next-jdbc {:builder-fn result-set/as-unqualified-maps}))
        m (h/map-of-db-fns "sql/queries.sql")
        jdbc-url (-> (system-state)
                     :postgres/server
                     :jdbc-url)
        datasource-options {:jdbcUrl jdbc-url
                            :maximumPoolSize 10
                            :minimumIdle 1
                            :idleTimeout 600000
                            :connectionTimeout 30000}
        depot-options {:limit 100
                       :file "sql/queries.sql"}
        delete-all-users-records! (get-in m [:delete-all-users-records! :fn])

        insert-user! (get-in m [:insert-user! :fn])
        ds (connection/->pool HikariDataSource datasource-options)
        _ (delete-all-users-records! ds)
        insert-uuid (random-uuid)
        module (module [setup topologies]

                       (declare-object setup *jdbc-depot (->jdbc-depot
                                                          datasource-options
                                                          depot-options
                                                          nil
                                                          nil))
                       (let [s (stream-topology topologies "print-records")]
                         (declare-pstate s $$user-profile (map-schema String (fixed-keys-schema
                                                                              {:user (fixed-keys-schema
                                                                                      {:anonymous-ids (vector-schema String)})
                                                                               :users (fixed-keys-schema
                                                                                       {:friends (vector-schema Integer)})})))
                         (<<sources s
                                    (source> *jdbc-depot :> *data)
                                    (anchor> <default-root>)
                                    (pp/pprint (into (sorted-map) *data))
                                    (hook> <default-root>)
                                    (pp/pprint (into (sorted-map) *data)))))]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks 4 :threads 2})
      (Thread/sleep 10000)
      (with-open [conn (jdbc/get-connection ds)]
        (insert-user! ds (clj->pg conn {:id insert-uuid
                                        :user-id insert-uuid
                                        :friends [1 2 3]})))
      (Thread/sleep 10000)
      (rtest/destroy-module! ipc (get-module-name module)))
    (is true)))

(comment
  (let [_ (h/set-adapter! (next-adapter/hugsql-adapter-next-jdbc {:builder-fn result-set/as-unqualified-maps}))
        m (h/map-of-db-fns "sql/queries.sql")
        jdbc-url (-> (system-state)
                     :postgres/server
                     :jdbc-url)
        datasource-options {:jdbcUrl jdbc-url
                            :maximumPoolSize 10
                            :minimumIdle 1
                            :idleTimeout 600000
                            :connectionTimeout 30000}
        depot-options {:limit 100
                       :file "sql/queries.sql"}
        get-user-profile (get-in m [:get-user-profile :fn])
        ds (connection/->pool HikariDataSource datasource-options)]
    (with-open [conn (jdbc/get-connection ds)]
      (get-user-profile ds {:user-id "foo"}))))
