(ns amiorin.rama-jdbc
  (:require
   [aero.core :as aero]
   [babashka.fs :as fs]
   [big-config :as bc]
   [big-config.core :as core]
   [big-config.lock :as lock]
   [big-config.run :as run]
   [big-config.step :as step]
   [big-config.step-fns :as step-fns]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.grzm.awyeah.client.api :as aws]
   [com.grzm.awyeah.credentials :refer [profile-credentials-provider]]
   [org.corfield.new :as new]
   [ring.util.codec :as codec])
  (:import
   [java.net URL]))

(defn invoke [client operation request]
  (let [response (aws/invoke client {:op operation :request request})]
    (if (contains? response :cognitect.anomalies/category)
      (throw (ex-info "AWS operation failed" {:operation operation :request request :response response}))
      response)))

(defn ->jdbc-url-staging
  []
  (let [client (aws/client {:api :secretsmanager
                            :region "eu-west-1"
                            :credentials-provider (profile-credentials-provider "fixme-an-aws-profile")})]
    (-> (invoke client :GetSecretValue {:SecretId "fixme-provide-a-secret-id"
                                        :Query :SecretString})
        :SecretString
        (json/decode true)
        :JDBC_URL)))

(defn parse-jdbc-url [url-string]
  (let [url (URL. url-string)
        query-string (.getQuery url)
        parsed-params (if query-string
                        (codec/form-decode query-string)
                        {})
        user (get parsed-params "user")
        password (get parsed-params "password")]
    {:postgres-host-staging (.getHost url)
     :postgres-port-staging (let [p (.getPort url)] (if (= p -1) nil p))
     :postgres-dbname-staging (str/replace (.getPath url) #"/" "")
     :postgres-user-staging user
     :postgres-password-staging password}))

(defn psql-map
  [url]
  (some-> url
          (str/replace #".*://" "https://")
          parse-jdbc-url))

(defn port-assigner [service]
  (-> (fs/cwd)
      (str service)
      hash
      abs
      (mod 64000)
      (+ 1024)))

(defn data-fn
  [data]
  (let [project-root (fs/parent (fs/cwd))
        postgres-home (fs/path project-root "postgres")
        postgres-data (fs/path postgres-home "data")
        jdbc-url-staging (try (->jdbc-url-staging)
                              (catch Exception _
                                "jdbc:postgresql://localhost:5432/rama?password=rama&user=rama&sslmode=disable"))]
    (-> data
        (assoc :project-root project-root)
        (assoc :duckdb-port (port-assigner "duckdb"))
        (assoc :postgres-port (port-assigner "postgres"))
        (assoc :postgres-port-test (port-assigner "postgres-test"))
        (assoc :postgres-home postgres-home)
        (assoc :postgres-user "rama")
        (assoc :postgres-dbname "rama")
        (assoc :postgres-schema "rama")
        (assoc :postgres-data postgres-data)
        (merge (psql-map jdbc-url-staging)))))

(defn template-fn
  [edn _data]
  (update edn :transform #(into (or % []) [["build" "resources/sql"
                                            {"queries.sql" "{{table-name}}.sql"}
                                            :only]
                                           ["build" "migrations"
                                            {"trigger.sql" "90_{{table-name}}.sql"}
                                            :only]])))

(defn post-process-fn
  [edn data])

(defn opts->dir
  [opts]
  (or (::bc/target-dir opts) (throw (ex-info ":big-config/target-dir is missing from opts" {:opts opts}))))

(defn build-fn [{:keys [::module ::profile] :as opts}]
  (binding [*out* (java.io.StringWriter.)]
    (let [default-opts {:template "amiorin/rama-jdbc"
                        :name "amiorin/rama-jdbc"
                        :target-dir (opts->dir opts)
                        :module module
                        :profile profile
                        :overwrite true}
          tables [["users" "UUID"]
                  ["posts" "UUID"]]]
      (doseq [[table-name
               primary-key-type] tables]
        (new/create (merge default-opts {:table-name table-name
                                         :primary-key-type primary-key-type})))))
  (core/ok opts))

(defn opts-fn [opts]
  (merge opts {::lock/lock-keys [::module ::profile]
               ::run/shell-opts {:dir (opts->dir opts)}}))

(defn run-steps
  ([s opts]
   (run-steps s [step/print-step-fn
                 (step-fns/->exit-step-fn ::step/end)
                 (step-fns/->print-error-step-fn ::step/end)] opts))
  ([s step-fns opts]
   (apply run-steps step-fns opts (step/parse s)))
  ([step-fns opts steps cmds module profile]
   (let [opts (-> "amiorin/rama_jdbc/config.edn"
                  io/resource
                  aero/read-config
                  (merge (or opts {})
                         {::step/steps steps
                          ::run/cmds cmds
                          ::module module
                          ::profile profile})
                  opts-fn)
         run-steps (step/->run-steps build-fn)]
     (run-steps step-fns opts))))

(comment
  (run-steps "build -- setup prod"
             {::bc/env :repl
              ::bc/target-dir ".."}))
