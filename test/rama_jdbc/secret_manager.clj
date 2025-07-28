(ns rama-jdbc.secret-manager
  (:require
   [cheshire.core :as json]
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.client.shared :refer [http-client]]
   [cognitect.aws.credentials :refer [default-credentials-provider]]))

(defn invoke [client operation request]
  (let [response (aws/invoke client {:op operation :request request})]
    (if (contains? response :cognitect.anomalies/category)
      (throw (ex-info "AWS operation failed" {:operation operation :request request :response response}))
      response)))

(defn ->jdbc-url
  []
  (let [client (aws/client {:api :secretsmanager
                            :region "eu-west-1"
                            :credentials-provider (default-credentials-provider (http-client))})]
    (-> (invoke client :GetSecretValue {:SecretId "fixme-provide-a-secret-id"
                                        :Query :SecretString})
        :SecretString
        (json/decode true))))
