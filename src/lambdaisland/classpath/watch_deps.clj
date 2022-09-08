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
           java.nio.file.Paths
           java.nio.file.Path))

(def watcher (atom nil))

(defn path ^Path [root & args]
  (if (and (instance? Path root) (not (seq args)))
    root
    (Paths/get (str root) (into-array String args))))

(defn canonical-path [p]
  (.toRealPath (path p) (into-array LinkOption [])))

(defn parent-path [p]
  (.getParent (path p)))

(def process-root-path (canonical-path "."))

(defn basis
  "Default function for (re-)computing the tools.deps basis, which we then use to
  update the classpath. Delegates to [[deps/create-basis]], with one addition:
  if you include an `:extra` option which points at a file (string), then we
  also look in that file for a `:lambdaisland.classpath/aliases`, which are
  additional alias keys to load. This allows having a `deps.local.edn`, where
  you can change the aliases in use without restarting."
  [opts]
  (if-let [f (:basis-fn opts)]
    (f opts)
    (deps/create-basis
     (if (string? (:extra opts))
       (update opts :aliases into (:lambdaisland.classpath/aliases
                                   (deps/slurp-deps (io/file (:extra opts)))))
       opts))))

(defn- on-event [deps-paths opts {:keys [type path]}]
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
               (some #{path} deps-paths))
      (try
        (println "[watch-deps] ✨ Reloading"
                 (str (.relativize process-root-path path))
                 "✨")
        (let [added-paths (remove (set (map str (cp/system-classpath)))
                                  (:classpath-roots (basis opts)))]
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
           (let [basis (basis opts)
                 deps-paths (cond-> [(path process-root-path "deps.edn")]
                              (:include-local-roots? opts)
                              (into (->> (vals (:libs basis))
                                         (keep :local/root)
                                         (map canonical-path)
                                         (map #(path % "deps.edn"))))
                              (string? (:extra opts))
                              (conj (canonical-path (:extra opts))))
                 roots (group-by parent-path deps-paths)]
             (prn roots)
             (doall
              (for [[root deps-paths] roots]
                (beholder/watch
                 (partial #'on-event deps-paths opts)
                 (str root))))))))

(defn stop!
  "Stop a previously started watcher"
  [& _]
  (swap! watcher
         (fn [w]
           (run! beholder/stop w)
           nil)))

(comment
  (start! {:aliases [:dev]
           :extra "deps.local.edn"})

  (stop!)
  (deps/create-basis {:aliases [:backend]
                      :extra '{cider/cider-nrepl #:mvn{:version "0.28.5"}
                               refactor-nrepl/refactor-nrepl #:mvn{:version "3.5.2"}}})
  (remove (set (map str (cp/system-classpath)))
          (:classpath-roots (deps/create-basis opts))))
