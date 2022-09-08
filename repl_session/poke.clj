(ns poke
  (:require [lambdaisland.classpath :as licp]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import clojure.lang.DynamicClassLoader))

(licp/classpath-chain)
(licp/classloader-chain)

(licp/update-classpath! {:extra {:paths ["repl_session/"]}})

(licp/debug! false)

(licp/resources "poke.clj")

(map (juxt licp/cl-id #(.getResource % "poke.clj"))
     (licp/classloader-chain))

(+ 1 1)

(defn has-dcl?
  "Is this classloader or any of its ancestors a DynamicClassLoader?"
  ^DynamicClassLoader
  [^ClassLoader cl]
  (loop [loader cl]
    (when loader
      (if (instance? DynamicClassLoader loader)
        true
        (recur (.getParent loader))))))

(has-dcl? (licp/parent (licp/root-loader)))

(keep (fn [{:local/keys [root]}]
        (when (and root (.exists (io/file root "shadow-cljs.edn")))
          root))
      (vals (:libs (licp/read-basis))))

(io/resource "public/index.html")
