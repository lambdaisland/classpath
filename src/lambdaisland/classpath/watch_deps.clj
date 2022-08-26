(ns lambdaisland.classpath.watch-deps
  "Watch deps.edn for changes"
  (:require [clojure.java.classpath :as cp]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.deps.alpha :as deps]
            [lambdaisland.classpath :as licp]
            [nextjournal.beholder :as beholder])
  (:import java.util.regex.Pattern
           java.nio.file.LinkOption
           java.nio.file.Paths))

(def watcher (atom nil))

(defn canonical-path [p]
  (.toRealPath (Paths/get p (into-array String [])) (into-array LinkOption [])))

(def process-root-path (canonical-path "."))

(defn- on-event [deps-path opts {:keys [type path]}]
  (locking watcher
    (when (and (= :modify type)
               ;; Before we used "." as the watch path, resulting in a
               ;; difference between mac, where the path would look like this
               ;; `/Users/x/project/./deps.edn`, vs Linux where the path would
               ;; look like this `./deps.edn`.
               ;;
               ;; We now turn `"."` into a canonical path before starting the
               ;; watcher, which means we get fully qualified filenames for both
               ;; in this equality check.
               (= path deps-path))
      (try
        (println "[watch-deps] ✨ Reloading"
                 (str (.relativize process-root-path path))
                 "✨")
        (let [added-paths (remove (set (map str (cp/system-classpath)))
                                  (:classpath-roots (deps/create-basis opts)))]
          (when (not (seq added-paths))
            (println "[watch-deps] No new libraries to add."))
          (doseq [path added-paths]
            (println "[watch-deps] +" (str/replace path #"^.*/\.m2/repository/" "")))
          (licp/install-priority-loader! added-paths))
        (catch Exception e
          (println "[watch-deps] Error while reloading deps.edn")
          (println e))))))

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
             (println "Stopping existing `deps.edn' watchers")
             (run! beholder/stop w))
           (let [basis (deps/create-basis opts)
                 roots (cons (str process-root-path)
                             (when (:include-local-roots? opts)
                               (->> (vals (:libs basis))
                                    (keep :local/root)
                                    (map canonical-path)
                                    (map str))))]
             (doall
              (for [root roots]
                (beholder/watch
                 (partial #'on-event (Paths/get root (into-array String ["deps.edn"])) opts)
                 root)))))))

(defn stop!
  "Stop a previously started watcher"
  [opts]
  (swap! watcher
         (fn [w]
           (when w
             (beholder/stop w))
           nil)))

(comment
  (start! {:aliases [:dev]})

  (deps/create-basis {:aliases [:backend]
                      :extra '{cider/cider-nrepl #:mvn{:version "0.28.5"}
                               refactor-nrepl/refactor-nrepl #:mvn{:version "3.5.2"}}})
  (remove (set (map str (cp/system-classpath)))
          (:classpath-roots (deps/create-basis opts))))
