(ns rama-jdbc
  (:require
   [babashka.fs :as fs]
   [big-config :as bc]
   [big-config.render :as render]
   [big-config.run :as run]
   [big-config.step :as step]
   [big-config.utils :refer [port-assigner]]
   [cheshire.core :as json]
   [clojure.string :as str]
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.credentials :refer [profile-credentials-provider]]
   [ring.util.codec :as codec])
  (:import
   [java.net URL]))

(defn run-steps [s opts & step-fns]
  (let [dir ".."
        template {:template "rama-jdbc"
                  :target-dir dir
                  :overwrite true
                  :data-fn 'rama-jdbc/data-fn
                  :transform [["root" ""
                               {"envrc" ".envrc"}
                               {:tag-open \<
                                :tag-close \>}]]}
        templates (for [[table-name primary-key-type] [["users" "UUID"] ["posts" "UUID"]]]
                    (-> template
                        (merge {:table-name table-name
                                :primary-key-type primary-key-type})
                        (assoc :transform [["sql" "resources/sql"
                                            {"queries.sql" "{{table-name}}.sql"}
                                            :only]
                                           ["sql" "migrations"
                                            {"trigger.sql" "90_{{table-name}}.sql"}
                                            :only]])))
        templates (conj templates template)
        opts (merge opts
                    {::run/shell-opts {:dir dir
                                       :extra-env {"AWS_PROFILE" "default"}}
                     ::render/templates templates})]
    #_opts
    (if step-fns
      (apply step/run-steps s opts step-fns)
      (step/run-steps s opts))))

(comment
  (run-steps "render -- generic prod" {::bc/env :repl}))

(defn invoke [client operation request]
  (let [response (aws/invoke client {:op operation :request request})]
    (if (contains? response :cognitect.anomalies/category)
      (throw (ex-info "AWS operation failed" {:operation operation :request request :response response}))
      response)))

(defn secrets->map
  [& {:keys [credentials-provider region secret-id]}]
  (let [client (aws/client {:api :secretsmanager
                            :region region
                            :credentials-provider credentials-provider})]
    (-> (invoke client :GetSecretValue {:SecretId secret-id
                                        :Query :SecretString})
        :SecretString
        (json/decode true))))

(defn ->jdbc-url-staging
  []
  (-> (secrets->map :credentials-provider (profile-credentials-provider "fixme-an-aws-profile")
                    :region "eu-west-1"
                    :secret-id "fixme-provide-a-secret-id")
      :JDBC_URL))

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

(defn data-fn
  [data _]
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

(comment
  (data-fn {} nil))
