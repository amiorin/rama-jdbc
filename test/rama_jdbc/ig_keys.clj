(ns rama-jdbc.ig-keys
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [destroy process shell]]
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
  [_ {:keys [jdbc-url port pc-port env]}]
  (let [ready-file (->ready-file port)
        _ (fs/delete-if-exists ready-file)
        cmd (format "direnv exec . process-compose -f pc-%s.yaml -p %s up" (name env) pc-port)
        pc (process {:err (log :err)
                     :out (log :out)} cmd)]
    (wait-for-path ready-file {:timeout 5000})
    {:pc pc
     :jdbc-url jdbc-url}))

(defmethod ig/halt-key! :postgres/server
  [_ {:keys [pc]}]
  (destroy pc))

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
