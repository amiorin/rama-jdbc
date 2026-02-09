(ns rama-jdbc.system
  (:require
   [babashka.process :as p]
   [big-config :as bc]
   [big-config.core :refer [->workflow ok]]
   [big-config.run :as run]
   [big-config.step-fns :refer [log-step-fn]]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- keywordize [s]
  (-> (str/lower-case s)
      (str/replace "_" "-")
      (str/replace "." "-")
      keyword))

(defn- read-system-env []
  (->> (System/getenv)
       (map (fn [[k v]] [(keywordize k) v]))
       (into {})))

(def env (read-system-env))

(defn destroy-forcibly [proc]
  (.destroyForcibly ^java.lang.Process (:proc proc))
  proc)

(defn start-and-grep [cmd regex]
  (let [proc (p/process {:err :out} cmd) ;; Redirect stderr to see errors
        reader (io/reader (:out proc))]
    (try
      (loop []
        (if-let [line (.readLine reader)]
          (do
            (binding [*out* *err*]
              (println line)
              (.flush *err*))
            (if (re-find regex line)
              [proc line]
              (do (Thread/sleep 100)
                  (recur))))
          (throw (Exception. "Stream closed before regex was found"))))
      (catch Exception e
        (destroy-forcibly proc) ;; Clean up if things go south
        (throw e)))))

(defn prepare [{:keys [::profile] :as opts}]
  (let [profile-name (name profile)
        profile-opts (-> (case profile
                           :dev {::jdbc-url (env :jdbc-url-dev)
                                 ::pg-port (env :pg-port-dev)}
                           :test {::jdbc-url (env :jdbc-url-test)
                                  ::pg-port (env :pg-port-test)})
                         (merge {::pg-data-dir (format ".postgres/%s" profile-name)
                                 ::pg-user "rama"
                                 ::pg-db "rama"
                                 ::user (System/getProperty "user.name")
                                 ::env profile-name}))]
    (merge opts (ok) profile-opts)))

(defn initdb [step-fns {:keys [::pg-data-dir] :as opts}]
  (let [opts (->> (merge opts {::run/shell-opts {:err *err*
                                                 :out *err*}
                               ::run/cmds [(format "rm -rf %s" pg-data-dir)
                                           (format "initdb %s" pg-data-dir)]})
                  (run/run-cmds step-fns))]
    (.flush *err*)
    opts))

(defn start-postgres [{:keys [::pg-data-dir ::pg-port] :as opts}]
  (let [cmd (format "postgres -c log_statement=all -D %s -p %s" pg-data-dir pg-port)
        [proc _] (start-and-grep cmd #".*database system is ready to accept connections.*")]
    (merge opts (ok) {::pg-proc proc})))

(defn configure-postgres [step-fns {:keys [::pg-port ::pg-user ::pg-db ::user ::env] :as opts}]
  (let [opts (->> (merge opts {::run/shell-opts {:err *err*
                                                 :out *err*}
                               ::run/cmds [(format "createuser -h localhost -p %s -U %s --no-password %s" pg-port user pg-user)
                                           (format "createdb -h localhost -p %s -U %s -O %s %s" pg-port user pg-user pg-db)
                                           (format "sql-migrate up -env=%s -config=migrations/dbconfig.yml" env)]})
                  (run/run-cmds step-fns))]
    (.flush *err*)
    opts))

(defn stop [{:keys [::pg-proc ::pg-data-dir ::async] :as opts}]
  (let [clean-up (fn [opts]
                   (when pg-proc
                     (destroy-forcibly pg-proc))
                   (run/generic-cmd :opts opts :cmd (format "rm -rf %s" pg-data-dir))
                   opts)]
    (if async
      (assoc opts ::stop #(clean-up opts))
      (clean-up opts))))

(def state (->workflow {:first-step ::start
                        :wire-fn (fn [step step-fns]
                                   (case step
                                     ::start [prepare ::initdb]
                                     ::initdb [(partial initdb step-fns) ::start-postgres]
                                     ::start-postgres [start-postgres ::configure-postgres]
                                     ::configure-postgres [(partial configure-postgres step-fns) ::end]
                                     ::end [stop]))}))

(defn stop! [system-state]
  (when-let [f (::stop system-state)]
    (f)))

(comment
  (do
    (require '[user :as u])
    (reset! u/debug-atom [])
    (into (sorted-map) (state [log-step-fn] {::bc/env :repl
                                             ::profile :dev}))
    #_(-> @u/debug-atom))
  (let [system-state (atom (into (sorted-map) (state [log-step-fn] {::bc/env :repl
                                                                    ::async true
                                                                    ::profile :dev})))]
    (stop! @system-state)
    @system-state)
  (let [system-state (atom (into (sorted-map) (state [log-step-fn] {::bc/env :repl
                                                                    ::async true
                                                                    ::profile :test})))]
    (stop! @system-state)
    @system-state))
