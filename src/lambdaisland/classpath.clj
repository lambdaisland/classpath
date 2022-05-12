(ns lambdaisland.classpath
  (:require [rewrite-clj.zip :as z]
            [clojure.string :as str]
            [clojure.tools.deps.alpha :as deps]
            [clojure.tools.gitlibs :as gitlibs]
            [clojure.tools.gitlibs.impl :as gitlibs-impl]
            [clojure.java.classpath :as cp]
            [clojure.java.io :as io]
            [lambdaisland.shellutils :as shellutils])
  (:import (java.util.jar JarFile JarEntry)
           (java.io File)
           (java.lang ClassLoader)
           (java.net URL URLClassLoader)
           (clojure.lang DynamicClassLoader)))

#_(set! *warn-on-reflection* true)

(defn read-basis
  "Read the basis (extended deps.edn) that Clojure started with, using the
  `clojure.basis` system property."
  []
  (when-let [f (io/file (System/getProperty "clojure.basis"))]
    (if (and f (.exists f))
      (deps/slurp-deps f)
      (throw (IllegalArgumentException. "No basis declared in clojure.basis system property")))))

(defn git-pull-lib* [loc lib]
  (let [coords (z/sexpr loc)
        git-url (:git/url coords)
        git-dir (gitlibs-impl/git-dir git-url)
        _ (when-not (.isDirectory (io/file git-dir))
            (gitlibs-impl/git-clone-bare git-url git-dir))
        _ (gitlibs-impl/git-fetch git-dir)
        sha (some #(and % (gitlibs-impl/git-rev-parse (str git-dir) %))
                  [(:git/branch coords)
                   "main"
                   "master"])]
    (z/assoc loc :git/sha sha)))

(defn git-pull-lib
  "Update the :git/sha in deps.edn for a given library to the latest sha in a branch

  Uses `:git/branch` defaulting to `main`"
  ([lib]
   (git-pull-lib "deps.edn" lib))
  ([deps-file lib]
   (let [loc (z/of-file deps-file)]
     (spit deps-file
           (z/root-string
            (as-> loc $
              (if-let [loc (z/get (z/get $ :deps) lib)]
                (z/up (z/up (git-pull-lib* loc lib)))
                $)

              (if-let [aliases (some-> $ (z/get :aliases) z/sexpr)]
                (reduce (fn [loc alias]
                          (reduce
                           (fn [loc dep-type]
                             (if-let [loc (some-> loc
                                                  (z/get alias)
                                                  (z/get dep-type)
                                                  (z/get lib))]
                               (z/up (z/up (z/up (git-pull-lib* loc lib))))
                               loc))
                           loc
                           [:extra-deps :override-deps :default-deps]))
                        $
                        (keys aliases))
                $)))))))

(defn classpath
  "clojure.java.classpath does not play well with the post-Java 9 application
  class loader, which is no longer a URLClassLoader, even though ostensibly it
  tries to cater for this, but in practice if any URLClassLoader or
  DynamicClassLoader higher in the chain contains a non-empty list of URLs, then
  this shadows the system classpath."
  []
  (distinct (concat (cp/classpath) (cp/system-classpath))))

(defn context-classloader
  "Get the context classloader for the current thread"
  ^ClassLoader
  ([]
   (context-classloader (Thread/currentThread)))
  ([^Thread thread]
   (.getContextClassLoader thread)))

(defn base-loader
  "Get the loader that Clojure uses internally to load files

  This is usually the current context classloader, but can also be
  clojure.lang.Compiler/LOADER."
  ^ClassLoader []
  (clojure.lang.RT/baseLoader))

(defn cl-name
  "Get a classloaders's defined name"
  [^ClassLoader cl]
  (.getName cl))

(defn cl-id
  "return a symbol identifying the cl, mainly meant for concise printing"
  [^ClassLoader cl]
  (if cl
    (symbol
     (or (.getName cl)
         (str cl)))
    'boot))

(defn dynamic-classloader?
  "Is the given classloader a [[clojure.lang.DynamicClassLoader]]"
  [cl]
  (instance? clojure.lang.DynamicClassLoader cl))

(defn priority-classloader?
  "Is the given classloader a [[priority-classloader]]"
  [cl]
  (when-let [name (and cl (cl-name cl))]
    (str/starts-with? name "lambdaisland/priority-classloader")))

(defn root-loader
  "Find the bottom-most DynamicClassLoader in the chain of parent classloaders"
  ^DynamicClassLoader
  ([]
   (root-loader (base-loader)))
  ([^ClassLoader cl]
   (when cl
     (loop [loader cl]
       (let [parent (.getParent loader)]
         (cond
           (or (dynamic-classloader? parent)
               (priority-classloader? parent))
           (recur parent)

           (or (dynamic-classloader? loader)
               (priority-classloader? loader))
           loader))))))

(defn app-loader
  "Get the application (aka system) classloader"
  ^ClassLoader []
  (ClassLoader/getSystemClassLoader))

(defn platform-loader
  "Get the platform classloader"
  ^ClassLoader []
  (ClassLoader/getPlatformClassLoader))

(defn compiler-loader
  "Get the clojure.lang.Compiler/LOADER, if set

  This is the loader Clojure uses to load code with, if the var is set. If not
  it falls back to the context classloader."
  []
  @clojure.lang.Compiler/LOADER)

(defn dynamic-classloader
  "Construct a new DynamicClassLoader"
  ^DynamicClassLoader
  [^ClassLoader parent]
  (clojure.lang.DynamicClassLoader. parent))

(defn parent
  "Get the parent classloader"
  ^ClassLoader [^ClassLoader cl]
  (.getParent cl))

(defn classpath-directories
  "Returns a sequence of File objects for the directories on classpath."
  []
  (filter #(.isDirectory ^File %) (classpath)))

(defn classpath-jarfiles
  "Returns a sequence of JarFile objects for the JAR files on classpath."
  []
  (map #(JarFile. ^File %) (filter cp/jar-file? (classpath))))

(defn find-resources
  "Scan 'the classpath' for resources that match the given regex."
  [regex]
  ;; FIXME currently jar entries always come first in the result, this should be
  ;; in classpath order.
  (concat
   (sequence
    (comp
     (mapcat #(iterator-seq (.entries ^JarFile %)))
     (map #(.getName ^JarEntry %))
     (filter #(re-find regex %)))
    (classpath-jarfiles))

   (sequence
    (comp
     (mapcat file-seq)
     (map str)
     (filter #(re-find regex %)))
    (classpath-directories))))

(defn file->ns-name
  "Get the ns name for a given clj file name"
  [filename]
  (-> filename
      (str/replace #"\.clj$" "")
      (str/replace #"/" ".")
      (str/replace #"_" "-")))

(defn classloader-chain
  "Get the chain of parent classloaders, all the way to the system AppClassLoader
  and PlatformClassLoader."
  ([]
   (classloader-chain (base-loader)))
  ([cl]
   (take-while identity (iterate parent cl))))

(defn classpath-chain
  "Return a list of classloader names, and the URLs they have on their classpath

  Mainly meant for inspecting the current state of things."
  ([]
   (classpath-chain (context-classloader)))
  ([cl]
   (for [^ClassLoader cl (classloader-chain cl)]
     [(cl-id cl)
      (map str (cond
                 (instance? URLClassLoader cl)
                 (.getURLs ^URLClassLoader cl)
                 (= "app" (.getName cl))
                 (cp/system-classpath)))])))

(defn resources
  "The plural of [[clojure.java.io/resource]], find all resources with the given
  name on the classpath.

  Useful for checking shadowing issues, in case a library ended up on the
  classpath multiple times."
  ([name]
   (resources (base-loader) name))
  ([^ClassLoader cl name]
   (enumeration-seq (.getResources cl name))))

(defn priority-classloader
  "A modified URLClassloader

  It will give precedence to its own URLs over its parents, then to whatever
  resources its immediate parent returns, and only then passing the request up
  the chain, which will then proceed with the bottom most classloaders (Boot,
  then Platform, then App).

  We install this as the child of the bottom most
  clojure.lang.DynamicClassloader that we find.

  The logic here relies on the fact that DynamicClassLoader or its parent
  URLClassLoader do not implement the `getResource`/`getResources` methods, they
  rely on the parent implementation in ClassLoader, which gives precedence to
  ancestors, before proceeding to call `findResource`/`findResources`, which
  URLClassLoader/DynamicClassloader do implement. This classloader reverses that
  logic, so that the system classloader doesn't shadow our own classpath
  entries."
  [cl urls]
  (let [parent-loader (parent cl)]
    (proxy [URLClassLoader] [^String (str "lambdaisland/"
                                          (gensym "priority-classloader"))
                             ^"[Ljava.net.URL;" (into-array URL urls)
                             ^ClassLoader cl]
      (getResource [name]
        ;; `cl` is assumed to be the bottom-most DynamcClassLoader, which is
        ;; sitting directly above the application classloader
        ;;
        ;; - priority-classloader
        ;; - DynamicClassLoader
        ;; - app classloader
        ;;
        ;; The normal lookup order is bottom to top, we reverse that here by
        ;; first checking our own classpath, then the DCL, and only then handing
        ;; it to the app cl, which can further traverse down the chain.
        (or (.findResource this name)
            ;; reflection warning because findResource is protected, but we're a
            ;; subclass so it seems to be ok?
            (.findResource cl name)
            (.getResource parent-loader name)))
      (getResources [name]
        (java.util.Collections/enumeration
         (distinct
          (mapcat
           enumeration-seq
           ;; reflection warning because findResource is protected, but we're a
           ;; subclass so it seems to be ok?
           [(.findResources this name)
            (.findResources cl name)
            (.getResources parent-loader name)])))))))

(def fg-red "\033[0;31m")
(def fg-green "\033[0;32m")
(def fg-yellow "\033[0;33m")
(def fg-blue "\033[0;34m")
(def fg-reset "\033[0m")

(defn debug-context-classloader* [ns meta-form ^Thread thread cl]
  (let [old-cl (context-classloader thread)
        chain (classloader-chain cl)
        old-chain (classloader-chain old-cl)
        merge-base (some (set old-chain) chain)
        short-id #(-> (cl-id %)
                      (str/replace #".*@" "")
                      (str/replace #".*/" ""))
        sym (symbol (str "cl-" (short-id cl)))]
    (intern 'user sym (constantly cl))
    (println (str "[" (.getName thread) "]")
             (str fg-yellow ns ":" (:line meta-form) ":" (:column meta-form)
                  fg-blue " (user/" sym ")" fg-reset))
    (if (= cl old-cl)
      (do
        (println (str fg-yellow "  No-op" fg-reset)))
      (do
        (run!
         #(println fg-red " - " (cl-id %) '-> (short-id (parent %)) fg-reset)
         (take-while #(not= merge-base %) old-chain))
        (run!
         #(println fg-green " + " (cl-id %) '-> (short-id (parent %)) fg-reset)
         (take-while #(not= merge-base %) chain))))
    (.setContextClassLoader thread cl)))

(defmacro debug-context-classloader
  "Replace calls to `.setContextClassloader` with this to get insights into
  who/what/where/when/how is changing the classloader"
  [thread cl]
  `(debug-context-classloader* '~(ns-name *ns*) ~(meta &form) ~thread ~cl))

(defn debug? []
  (= "true" (System/getProperty "lambdaisland.classpath.debug")))

(defn debug!
  ([]
   (debug! true))
  ([enable?]
   (System/setProperty "lambdaisland.classpath.debug" (str enable?))))

(defn ensure-trailing-slash
  "URLClassPath looks for a trailing slash to determine whether something is a
  directory instead of a jar, so add trailing slashes to everything that doesn't
  look like a JAR."
  [^String path]
  (cond
    (or (.endsWith path ".jar") (.endsWith path "/"))
    path
    (.isDirectory (io/file path))
    (str path "/")
    :else
    path))

(defn install-priority-loader!
  "Install the new priority loader as immediate parent of the bottom-most
  DynamicClassloader, discarding any further descendants. After this the chain is

  [priority-classloader
   DynamicClassLoader
   AppClassLoader
   PlatformClassLoader]

  Do this for every thread that has a DynamicClassLoader as the context
  classloader, or any of its parents.

  We need to do this from a separate thread, hence the `future` call, because
  nREPL's interruptible-eval resets the context-classloader at the end of the
  evaluation, so this needs to happen after that has happened.

  Start the JVM with `-Dlambdaisland.classpath.debug=true` to get debugging
  output.
  "
  ([]
   (install-priority-loader! []))
  ([paths]
   (when (debug?)
     (println "Installing priority-classloader")
     (run! #(println "-" %) paths))
   (let [urls (map #(URL. (str "file:" (ensure-trailing-slash %))) paths)
         current-thread (Thread/currentThread)
         dyn-cl (or (root-loader (context-classloader current-thread))
                    (clojure.lang.DynamicClassLoader. (app-loader)))
         new-loader (priority-classloader dyn-cl urls)]
     ;; Install a priority-classloader in every thread that currently has a
     ;; DynamicClassLoader
     (future
       (try
         (Thread/sleep 100)
         (doseq [^Thread thread (.keySet (Thread/getAllStackTraces))
                 ;; Install the new loader in every thread that has a Clojure
                 ;; loader, and always in the thread this is invoked in, even if
                 ;; for some reason it does not yet have a Clojure loader
                 ;;
                 ;; We also consider threads that currently have the application
                 ;; loader set, this includes threads created by
                 ;; futures/agents (clojure-agent-send-off-pool-*), before
                 ;; `clojure.main/repl` installed its DynamicClassLoader
                 :when (or (= thread current-thread)
                           (root-loader (context-classloader thread))
                           (= (app-loader) (context-classloader thread)))]
           (if (debug?)
             (debug-context-classloader thread new-loader)
             (.setContextClassLoader thread new-loader)))
         (catch Exception e
           (println "Error in" `install-priority-loader! e)
           (.printStackTrace e))))

     ;; Force orchard to use "our" classloader. This is a bit of nuclear option,
     ;; if we can clean up some of nREPLs classloader handling this should not
     ;; be necessary.
     #_(doseq [filename (find-resources #"orchard.*java/classpath.clj")]
         (try
           (alter-var-root
            (requiring-resolve (symbol (file->ns-name filename) "context-classloader"))
            (constantly (constantly new-loader)))
           (catch Exception e))))))

(defn update-classpath!
  "Use the given options to construct a basis (see [[deps/create-basis]]), then
  add any classpath-roots that aren't part of the system classpath yet to the
  classpath, by installing an extra classloader over Clojure's
  DynamicClassloader which takes precedence.

  This is the closest we can get to \"replacing\" the classpath. We can't remove
  any entries from the system classpath (the classpath the JVM booted with), but
  we can make sure any extra entries get precedence."
  [basis-opts]
  (install-priority-loader!
   (remove (set (map str (cp/system-classpath)))
           (:classpath-roots (deps/create-basis basis-opts)))))



(comment
  (git-pull-lib 'com.lambdaisland/webstuff)

  (update-classpath!
   '{:aliases [:dev :test :licp]
     :extra {:deps {com.lambdaisland/webstuff {:local/root "/home/arne/github/lambdaisland/webstuff"}}}})

  (classpath-chain)
  (resources "lambdaisland/webstuff/http.clj")
  (io/resource "lambdaisland/webstuff/http.clj")

  (classloader-chain)
  (classpath-chain)

  (io/resource "clojure/main.class")
  ;;=> #object[java.net.URL 0x3237dfe5 "jar:file:/home/arne/.m2/repository/org/clojure/clojure/1.10.3/clojure-1.10.3.jar!/clojure/main.class"]

  (.getResource ^ClassLoader loader "clojure/main.class")

  (defn xxx [])

  (.loadClass (clojure.lang.RT/baseLoader) "user$xxx")
  ;; user$xxx

  (.loadClass (ClassLoader/getPlatformClassLoader) "lambdaisland.classpath$xxx")
  ;; => java.lang.ClassNotFoundException


  (group-by second
            (map (juxt #(.getName %) #(some-> (.getClassLoader %) .getName))
                 (.modules (java.lang.ModuleLayer/boot))))


  )
