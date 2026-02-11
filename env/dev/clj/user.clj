(ns user
  (:require
   [big-config :as bc]
   [big-config.system :as system]
   [clojure.tools.namespace.repl :as repl] ;; benchmarking
   [integrant.core :as ig]
   [integrant.repl :refer [go halt reset]]
   [integrant.repl.state :as state]
   [rama-jdbc.components :as components]
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

(defonce debug-atom (atom []))
(defn add-to-debug [x]
  (swap! debug-atom conj x))
(add-tap add-to-debug)

(comment
  (reset! debug-atom [])
  (-> @debug-atom))

(defonce system (atom nil))

(defn with-system [f]
  (when @system
    (system/stop! @system))
  (reset! system (components/->system {::bc/env :repl
                                       ::components/profile :test
                                       ::system/async true}))
  (f))

(comment
  (-> system
      deref
      (->> (into (sorted-map))))
  (do
    (reset! debug-atom [])
    (let [f (fn []
              (let [{:keys [::components/profile]} @system]
                (tap> profile)))]
      (with-system f))
    (-> @debug-atom)))
