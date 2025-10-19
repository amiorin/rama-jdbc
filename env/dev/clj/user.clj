(ns user
  (:require
   [clojure.tools.namespace.repl :as repl] ;; benchmarking
   [integrant.core :as ig]
   [integrant.repl :refer [go halt reset]]
   [integrant.repl.state :as state]
   [rama-jdbc.ig-keys]
   [rama-jdbc.test-utils :refer [system-config]]))

(defn prep-with-profile! [profile]
  (integrant.repl/set-prep! #(-> {:profile profile}
                                 (system-config)
                                 (ig/expand))))

(prep-with-profile! :dev)

(repl/set-refresh-dirs "src" "test")

(defn start! []
  ;;please commit only (go)
  #_(go [:controllers/rules-engine-v2])
  (go))

(defn stop! []
  (halt))

(comment
  (go)
  (halt)
  (reset)
  [state/config state/preparer state/system])
