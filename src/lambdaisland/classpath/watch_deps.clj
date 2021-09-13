(ns lambdaisland.classpath.watch-deps
  "Watch deps.edn for changes"
  (:require [clojure.java.classpath :as cp]
            [clojure.string :as str]
            [clojure.tools.deps.alpha :as deps]
            [lambdaisland.classpath :as licp]
            [nextjournal.beholder :as beholder]))

(def watcher (atom nil))

(defn- on-event [opts {:keys [type path]}]
  (when (and (= :modify type)
             ;; On Mac the path will be absolute and include the watched dir,
             ;; e.g. `/Users/x/project/./deps.edn`
             ;; On other systems it seems to be relative, like `./deps.edn`
             (str/ends-with? (str path) "./deps.edn"))
    (println "✨ Reloading deps.edn ✨")
    (let [new-paths (remove (set (map str (cp/system-classpath)))
                            (:classpath-roots (deps/create-basis opts)))]
      (doseq [path new-paths]
        (println "- " path))
      (licp/install-priority-loader! new-paths))))

(defn start!
  "Start a file system watcher to pick up changes in `deps.edn'

  Options are passed on to tools.deps when creating the basis, you probably want
  to at least put the `:aliases` you need in there.

  ```
  (start! {:aliases [:dev :test]})
  ```
  "
  [opts]
  (swap! watcher
         (fn [w]
           (when w
             (println "Stopping existing `deps.edn' watcher")
             (beholder/stop w))
           (beholder/watch (partial on-event opts) "."))))

(defn stop!
  "Stop a previously started watcher"
  [opts]
  (swap! watcher
         (fn [w]
           (when w
             (beholder/stop w))
           nil)))


(comment
  (start! {:aliases [:dev]}))
