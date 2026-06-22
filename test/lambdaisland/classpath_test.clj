(ns lambdaisland.classpath-test
  (:require [clojure.test :refer [deftest is testing]]
            [lambdaisland.classpath :as licp])
  (:import (java.lang ClassLoader)
           (clojure.lang DynamicClassLoader)))

;; Pure function tests

(deftest file->ns-name-test
  (testing "converts path separators and underscores"
    (is (= "lambdaisland.classpath"
           (licp/file->ns-name "lambdaisland/classpath.clj")))
    (is (= "lambdaisland.some-ns"
           (licp/file->ns-name "lambdaisland/some_ns.clj")))
    (is (= "foo.bar.baz"
           (licp/file->ns-name "foo/bar/baz.clj")))))

(deftest ensure-trailing-slash-test
  (testing "jar paths are left unchanged"
    (is (= "/path/to/foo.jar"
           (licp/ensure-trailing-slash "/path/to/foo.jar"))))
  (testing "paths already ending in / are left unchanged"
    (is (= "/path/to/dir/"
           (licp/ensure-trailing-slash "/path/to/dir/"))))
  (testing "src directory on classpath gets a trailing slash"
    (let [src-path (.getAbsolutePath (clojure.java.io/file "src"))]
      (is (clojure.string/ends-with?
           (licp/ensure-trailing-slash src-path)
           "/")))))

(deftest cl-id-test
  (testing "nil classloader returns the boot symbol"
    (is (= 'boot (licp/cl-id nil))))
  (testing "a real classloader returns a symbol"
    (is (symbol? (licp/cl-id (ClassLoader/getSystemClassLoader))))))

(deftest dynamic-classloader?-test
  (testing "DynamicClassLoader is recognized"
    (is (licp/dynamic-classloader? (DynamicClassLoader. (ClassLoader/getSystemClassLoader)))))
  (testing "system classloader is not a DynamicClassLoader"
    (is (not (licp/dynamic-classloader? (ClassLoader/getSystemClassLoader))))))

;; README example tests

(deftest context-classloader-test
  (testing "(licp/context-classloader) returns a ClassLoader"
    (is (instance? ClassLoader (licp/context-classloader)))))

(deftest base-loader-test
  (testing "(licp/base-loader) returns a ClassLoader"
    (is (instance? ClassLoader (licp/base-loader)))))

(deftest root-loader-test
  (testing "(licp/root-loader) returns a DynamicClassLoader in a Clojure process"
    (is (instance? DynamicClassLoader (licp/root-loader)))))

(deftest compiler-loader-test
  (testing "(licp/compiler-loader) returns nil or a ClassLoader"
    (let [cl (licp/compiler-loader)]
      (is (or (nil? cl) (instance? ClassLoader cl))))))

(deftest classloader-chain-test
  (testing "(licp/classloader-chain) returns a non-empty seq of ClassLoaders"
    (let [chain (licp/classloader-chain)]
      (is (seq chain))
      (is (every? #(instance? ClassLoader %) chain)))))

(deftest classpath-chain-test
  (testing "(licp/classpath-chain) returns a non-empty seq of [id urls] pairs"
    (let [chain (licp/classpath-chain)]
      (is (seq chain))
      (is (every? #(= 2 (count %)) chain))
      (is (every? #(symbol? (first %)) chain))
      (is (every? #(sequential? (second %)) chain)))))
