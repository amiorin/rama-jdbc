(ns rama-clojure-starter.my-module
  (:require
   [clojure.java.io :as io]))

(use 'com.rpl.rama)
(use 'com.rpl.rama.path)
(require '[com.rpl.rama.ops :as ops])
(require '[com.rpl.rama.aggs :as aggs])
(require '[com.rpl.rama.test :as rtest])
(require '[clojure.string :as str])

(defmodule WordCountModule [setup topologies]
  (declare-depot setup *sentences-depot :random)
  (let [s (stream-topology topologies "word-count")]
    (declare-pstate s $$word-counts {String Long})
    (<<sources s
               (source> *sentences-depot :> *sentence)
               (str/split (str/lower-case *sentence) #" " :> *words)
               (ops/explode *words :> *word)
               (|hash *word)
               (+compound $$word-counts {*word (aggs/+count)}))))

(comment
  (def ipc (rtest/create-ipc))

  (rtest/launch-module! ipc WordCountModule {:tasks 4 :threads 2})

  (def sentences-depot (foreign-depot ipc (get-module-name WordCountModule) "*sentences-depot"))
  (def word-counts (foreign-pstate ipc (get-module-name WordCountModule) "$$word-counts"))

  (foreign-append! sentences-depot "Hello world")
  (foreign-append! sentences-depot "hello hello goodbye")
  (foreign-append! sentences-depot "Alice says hello")

  (foreign-select-one (keypath "hello") word-counts)
  (foreign-select-one (keypath "goodbye") word-counts))

(comment
  (def manager (open-cluster-manager))

  (def sentences-depot (foreign-depot manager (get-module-name WordCountModule) "*sentences-depot"))
  (def word-counts (foreign-pstate manager (get-module-name WordCountModule) "$$word-counts"))

  (foreign-append! sentences-depot "Hello world")
  (foreign-append! sentences-depot "hello hello goodbye")
  (foreign-append! sentences-depot "Alice says hello")

  (foreign-select-one (keypath "hello") word-counts)
  (foreign-select-one (keypath "goodbye") word-counts)

  (slurp (io/resource "rama.yaml")))
