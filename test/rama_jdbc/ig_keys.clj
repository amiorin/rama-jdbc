(ns rama-jdbc.ig-keys
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [process shell]]
   [babashka.wait :refer [wait-for-path]]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(defn log
  [out-or-err]
  (proxy [java.io.OutputStream] []
    (write
      [b off len]
      (let [s (String. b off len)]
        (doseq [line (str/split-lines s)]
          ((case out-or-err
             :err #(binding [*out* *err*] (log/info %))
             :out #(log/info %)) line))))))

(defmethod ig/init-key :system/env
  [_ env]
  env)

(defn ->ready-file
  [port]
  (format "/tmp/port-%s" port))

(defmethod ig/init-key :postgres/server
  [_ {:keys [jdbc-url port env]}]
  (let [ready-file (->ready-file port)
        _ (fs/delete-if-exists ready-file)
        postgres (process {:continue true
                           :err (log :err)
                           :out (log :out)} "bin/postgres.sh" port)]
    (wait-for-path ready-file {:timeout 5000})
    (shell {:err (log :err)
            :out (log :out)} (format "sql-migrate up -env=%s -config=migrations/dbconfig.yml" (name env)))
    {:postgres postgres
     :jdbc-url jdbc-url}))

(defmethod ig/halt-key! :postgres/server
  [_ {:keys [postgres]}]
  (let [pid (-> postgres :proc .pid)]
    (shell {:continue true
            :err (log :err)
            :out (log :out)} (format "kill -INT %s" pid))))

(comment
  (let [port "61865"
        _ (fs/delete-if-exists (->ready-file port))
        cmd (format "bin/postgres.sh %s" port)
        postgres (process {:err (log :err)
                           :out (log :out)} cmd)
        pid (-> postgres :proc .pid)]
    (wait-for-path (format "/tmp/port-%s" port) {:timeout 5000})
    (shell {:continue true
            :err (log :err)
            :out (log :out)} (format "kill -INT %s" pid))))
