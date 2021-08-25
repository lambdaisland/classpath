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
           (java.net URL URLClassLoader)))

(defn read-basis
  "Read the basis (extended deps.edn) that Clojure started with, using the
  `clojure.basis` system property."
  []
  (when-let [f (io/file (System/getProperty "clojure.basis"))]
    (if (and f (.exists f))
      (deps/slurp-deps f)
      (throw (IllegalArgumentException. "No basis declared in clojure.basis system property")))))

(defn git-pull-lib
  "Update the :git/sha in deps.edn for a given library to the latest sha in a branch

  Uses `:git/branch` defaulting to `main`"
  [lib]
  (let [deps-file "deps.edn"
        loc (z/get (z/get (z/of-file deps-file) :deps) lib)
        coords (z/sexpr loc)
        git-dir (gitlibs-impl/git-dir (:git/url coords))
        git-branch (:git/branch coords "main")
        _ (gitlibs-impl/git-fetch git-dir)
        sha (gitlibs-impl/git-rev-parse (str git-dir) git-branch)]
    (spit deps-file
          (z/root-string
           (z/assoc loc :git/sha sha)))))

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
  ^ClassLoader []
  (.getContextClassLoader (Thread/currentThread)))

(defn base-loader
  "Get the loader that Clojure uses internally to load files

  This is usually the current context classloader, but can also be
  clojure.lang.Compiler/LOADER."
  ^ClassLoader []
  (clojure.lang.RT/baseLoader))

(defn root-loader
  "Find the bottom-most DynamicClassLoader in the chain of parent classloaders"
  ^ClassLoader
  ([]
   (root-loader (base-loader)))
  ([^ClassLoader cl]
   (loop [loader cl]
     (let [parent (.getParent loader)]
       (if (or (instance? clojure.lang.DynamicClassLoader parent)
               (= (str `priority-classloader) (.getName parent)))
         (recur parent)
         loader)))))

(defn compiler-loader
  "Get the clojure.lang.Compiler/LOADER, if set

  This is the loader Clojure uses to load code with, if the var is set. If not
  it falls back to the context classloader."
  []
  @clojure.lang.Compiler/LOADER)

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
   (take-while identity (iterate #(.getParent %) cl))))

(defn classpath-chain
  "Return a list of classloader names, and the URLs they have on their classpath

  Mainly meant for inspecting the current state of things."
  []
  (for [cl (classloader-chain)]
    [(symbol
      (or (.getName cl)
          (str cl)))
     (map str (cond
                (instance? URLClassLoader cl)
                (.getURLs cl)
                (= "app" (.getName cl))
                (cp/system-classpath)))]))

(defn resources
  "The plural of [[clojure.java.io/resource]], find all resources with the given
  name on the classpath.

  Useful for checking shadowing issues, in case a library ended up on the
  classpath multiple times."
  ([name]
   (resources (base-loader) name))
  ([cl name]
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
  (let [cp-files (map io/as-file urls)
        find-resources (fn [^String name]
                         (mapcat (fn [^File cp-entry]
                                   (cond
                                     (and (cp/jar-file? cp-entry)
                                          (some #{name} (cp/filenames-in-jar (JarFile. cp-entry))))
                                     [(URL. (str "jar:file:" cp-entry "!/" name))]
                                     (.exists (io/file cp-entry name))
                                     [(URL. (str "file:" (io/file cp-entry name)))]))
                                 cp-files))]
    (proxy [URLClassLoader] [(str `priority-classloader) (into-array URL urls) cl]
      (getResource [name]
        (or (first (find-resources name))
            (.findResource (.getParent this) name)
            (.getResource (.getParent this) name)))
      (getResources [name]
        (java.util.Collections/enumeration
         (distinct
          (concat
           (find-resources name)
           (mapcat
            enumeration-seq
            [(.findResources (.getParent this) name)
             (.getResources (.getParent (.getParent this)) name)]))))))))

(defn install-priority-loader!
  "Install the new priority loader as immediate parent of the bottom-most
  DynamicClassloader, discarding any further descendants. After this the chain is

  [priority-classloader
   DynamicClassLoader
   AppClassLoader
   PlatformClassLoader]

  We also monkey patch any shaded orchard.java.classpath namespaces on the
  classpath (included with CIDER and refactor-nrepl) to use this classloader.

  We need to do this from a separate thread, hence the `future` call, because
  nREPL's interruptible-eval resets the context-classloader at the end of the
  evaluation, so this needs to happen after that has happened."
  ([]
   (install-priority-loader! []))
  ([paths]
   (let [thread (Thread/currentThread)
         new-loader (priority-classloader (root-loader) (map #(URL. (str "file://" %)) paths))]
     (future
       (Thread/sleep 100)
       (.setContextClassLoader thread new-loader)
       (doseq [filename (find-resources #"orchard.*java/classpath.clj")]
         (try
           (alter-var-root
            (requiring-resolve (symbol (file->ns-name filename) "context-classloader"))
            (constantly (constantly new-loader)))
           (catch Exception e)))))))

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
