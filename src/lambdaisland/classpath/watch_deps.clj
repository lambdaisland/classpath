(ns lambdaisland.classpath.watch-deps
  "Watch deps.edn for changes"
  (:require [clojure.java.classpath :as cp]
            [clojure.tools.deps.alpha :as deps]
            [lambdaisland.classpath :as licp])
  (:import [java.nio.file FileSystems StandardWatchEventKinds WatchEvent$Kind]
           [java.util Timer TimerTask]))

(def ^:private watcher (atom nil))
(def ^:private deps-hash (atom nil))

(defn- debounce
  [f timeout]
  (let [timer (Timer.)
        task (atom nil)]
    (fn [& args]
      (when-let [t ^TimerTask @task]
        (.cancel t))
      (let [new-task (proxy [TimerTask] []
                       (run []
                         (apply f args)
                         (.purge timer)
                         (reset! task nil)))]
        (reset! task new-task)
        (.schedule timer new-task timeout)))))

(defn- maybe-reload-deps
  [opts]
  (let [current-hash (hash (slurp "deps.edn"))
        last-hash @deps-hash]
    (when (not= last-hash current-hash)
      (println "✨ Reloading deps.edn ✨")
      (try
        (let [new-paths (remove (set (map str (cp/system-classpath)))
                                (:classpath-roots (deps/create-basis opts)))]
          (doseq [path new-paths]
            (println "- " path))
          (licp/install-priority-loader! new-paths))
        (catch Exception e
          (println "Failed to reload deps.edn:" (.getMessage e))))
      (reset! deps-hash current-hash))))

(def ^:private reload-deps (debounce maybe-reload-deps 250))

(defn- watch-project-root
  "Registers a `WatchService` on the project root and waits for `ENTRY_MODIFY`
   events for deps.edn"
  [opts]
  (let [file-system (FileSystems/getDefault)
        project-root (.getPath file-system "." (into-array String []))
        watch-service (.newWatchService file-system)]
    (future
      (try
        (loop [watch-key (.register project-root
                                    watch-service
                                    (into-array WatchEvent$Kind [StandardWatchEventKinds/ENTRY_MODIFY]))]

          (when (some #(= "deps.edn" (.toString (.context %)))
                      (.pollEvents watch-key))
            (reload-deps opts))
          (.reset watch-key)
          (recur
           (.take watch-service)))
        (catch InterruptedException _
          (println "Watcher stopped."))
        (catch Exception e
          (println "Watcher error:" (.getMessage e)))
        (finally
          (.close watch-service)
          (println "WatchService closed."))))))

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
             (future-cancel w))
           (watch-project-root opts))))

(defn stop!
  "Stop a previously started watcher"
  []
  (swap! watcher
         (fn [w]
           (when w
             (future-cancel w))
           nil)))


(comment
  (start! {:aliases [:dev]})
  (stop!))







