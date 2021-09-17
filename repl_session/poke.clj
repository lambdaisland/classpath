(ns poke
  (:require [lambdaisland.classpath :as licp]
            [clojure.string :as str]))

(licp/classpath-chain)
(licp/classloader-chain)

(licp/update-classpath! {:extra {:paths ["bar"]}})
