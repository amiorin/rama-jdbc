(ns rama-jdbc.components
  (:require
   [babashka.process :as p]
   [big-config :as bc]
   [big-config.core :refer [->workflow ok]]
   [big-config.run :as run]
   [big-config.step-fns :refer [log-step-fn]]
   [big-config.system :as system]))

(defn prepare [{:keys [::profile] :as opts}]
  (let [profile-name (name profile)
        profile-opts (-> (case profile
                           :dev {::jdbc-url (system/env :jdbc-url-dev)
                                 ::pg-port (system/env :pg-port-dev)}
                           :test {::jdbc-url (system/env :jdbc-url-test)
                                  ::pg-port (system/env :pg-port-test)})
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
        regex #".*database system is ready to accept connections.*"]
    (-> (system/re-program cmd regex ::pg-proc opts)
        (update ::system/stop-fns (fnil conj []) (fn [{:keys [::pg-proc ::pg-data-dir] :as opts}]
                                                   (when pg-proc
                                                     @(p/destroy-tree pg-proc)
                                                     @(system/destroy-forcibly pg-proc))
                                                   (run/generic-cmd :opts opts :cmd (format "rm -rf %s" pg-data-dir)))))))

(comment
  (start-postgres {::pg-data-dir "/asdfasdfasdfasd"
                   ::pg-port 4321}))

(defn configure-postgres [step-fns {:keys [::pg-port ::pg-user ::pg-db ::user ::env] :as opts}]
  (let [opts (->> (merge opts {::run/shell-opts {:err *err*
                                                 :out *err*}
                               ::run/cmds [(format "createuser -h localhost -p %s -U %s --no-password %s" pg-port user pg-user)
                                           (format "createdb -h localhost -p %s -U %s -O %s %s" pg-port user pg-user pg-db)
                                           (format "sql-migrate up -env=%s -config=migrations/dbconfig.yml" env)]})
                  (run/run-cmds step-fns))]
    (.flush *err*)
    opts))

(def ->system (->workflow {:first-step ::start
                           :wire-fn (fn [step step-fns]
                                      (case step
                                        ::start [prepare ::initdb]
                                        ::initdb [(partial initdb step-fns) ::start-postgres]
                                        ::start-postgres [start-postgres ::configure-postgres]
                                        ::configure-postgres [(partial configure-postgres step-fns) ::end]
                                        ::end [system/stop]))}))

(comment
  (do
    (require '[user :as u])
    (reset! u/debug-atom [])
    (into (sorted-map) (->system [log-step-fn] {::bc/env :repl
                                                ::profile :dev}))
    #_(-> @u/debug-atom))
  (let [system (atom (into (sorted-map) (->system [log-step-fn] {::bc/env :repl
                                                                 ::system/async true
                                                                 ::profile :dev})))]
    (system/stop! @system)
    @system)
  (let [system (atom (into (sorted-map) (->system [log-step-fn] {::bc/env :repl
                                                                 ::system/async true
                                                                 ::profile :test})))]
    (system/stop! @system)
    @system))
