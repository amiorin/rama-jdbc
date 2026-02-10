(ns rama-jdbc.system
  (:require
   [babashka.process :as p]
   [big-config :as bc]
   [big-config.core :refer [->workflow ok]]
   [big-config.run :as run]
   [big-config.step-fns :refer [log-step-fn]]
   [clojure.core.async :as a]
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
  (when (p/alive? proc)
    (.destroyForcibly ^java.lang.Process (:proc proc)))
  proc)

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

(defn re-stream [stream regex & {:keys [timeout]}]
  (let [signal-chan (a/chan (a/dropping-buffer 1))
        ms (or timeout 1000)]
    (a/thread
      (with-open [reader (io/reader stream)]
        (doseq [line (line-seq reader)]
          (binding [*out* *err*]
            (println line)
            (.flush *err*))
          (when (re-find regex line)
            (a/>!! signal-chan line)))))
    (let [[val port] (a/alts!! [signal-chan (a/timeout ms)])]
      (if (= port signal-chan)
        val
        :timeout))))

(defn re-program [cmd regex key opts]
  (let [proc (p/process {:err :out} cmd)
        stream (:out proc)]
    (case (re-stream stream regex {:timeout 500})
      :timeout (if (p/alive? proc)
                 (merge opts {key @(p/destroy-tree proc)
                              ::bc/exit 1
                              ::bc/err (format "regex `%s` not found in `%s`" regex cmd)})
                 (merge opts {key @proc
                              ::bc/exit 1
                              ::bc/err (format "`%s` exit with code `%s` before the timeout" cmd (:exit @proc))}))
      (merge opts {key proc
                   ::bc/exit 0
                   ::bc/err nil}))))

(comment
  (let [cmd #_"bash -c 'exit 1'" "bash -c 'for i in {10..1}; do echo $i; sleep 0.1; done;'"
        regex #"7"]
    (re-program cmd regex ::pg-proc {})))

(defn start-postgres [{:keys [::pg-data-dir ::pg-port] :as opts}]
  (let [cmd (format "postgres -c log_statement=all -D %s -p %s" pg-data-dir pg-port)
        regex #".*database system is ready to accept connections.*"]
    (re-program cmd regex ::pg-proc opts)))

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

(defn stop [{:keys [::pg-proc ::pg-data-dir ::async] :as opts}]
  (let [clean-up (fn [opts]
                   (when pg-proc
                     @(p/destroy-tree pg-proc)
                     @(destroy-forcibly pg-proc))
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
