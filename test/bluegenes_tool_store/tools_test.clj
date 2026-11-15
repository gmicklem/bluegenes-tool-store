(ns bluegenes-tool-store.tools-test
  "Tests for reading installed tools and the HTTP handler. No network access."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cheshire.core :as cheshire]
            [bluegenes-tool-store.core :as core]
            [bluegenes-tool-store.tools :as tools])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "tool-store-test" (make-array FileAttribute 0))))

(deftest parse-tool-test
  (let [dir  (temp-dir)
        tool (io/file dir "my-tool")]
    (io/make-parents (io/file tool "dist" "bundle.js"))
    (spit (io/file tool "dist" "bundle.js") "")
    (spit (io/file tool "package.json")
          (cheshire/generate-string {:name "my-tool" :version "1.2.3" :license "MIT"}))
    (spit (io/file tool "config.json")
          (cheshire/generate-string {:toolName {:human "My tool" :cljs "myTool"}}))
    (with-redefs [tools/tools-path dir]
      (let [parsed (tools/parse-tool "my-tool")]
        (is (= {:human "My tool" :cljs "myTool" :npm "my-tool"} (:names parsed)))
        (is (= "1.2.3" (get-in parsed [:package :version])))
        (is (false? (:hasimage parsed)))))
    (testing "a tool without config files is skipped rather than throwing"
      (with-redefs [tools/tools-path dir]
        (is (nil? (tools/parse-tool "missing")))))))

(deftest installed-tools-list-test
  (let [dir (temp-dir)
        cfg (io/file dir "tools.edn")]
    (spit cfg (pr-str {:tools {"a" "1.0.0" "b" "2.0.0"}}))
    (with-redefs [tools/tools-config cfg]
      (is (= #{"a" "b"} (set (tools/installed-tools-list)))))))

(deftest handler-test
  (testing "GET /api/tools/path returns the configured tools path"
    (let [res (core/handler {:request-method :get :uri "/api/tools/path" :headers {}})]
      (is (= 200 (:status res))))))

(deftest parse-port-test
  (testing "config.core parses numeric env vars to Long, which must still work"
    (is (= 5057 (core/parse-port {:server-port 5057})))
    (is (= 8080 (core/parse-port {:port 8080}))))
  (testing "string values from a config file work too"
    (is (= 5057 (core/parse-port {:server-port "5057"}))))
  (testing "server-port takes precedence over port, default is 5001"
    (is (= 1 (core/parse-port {:server-port 1 :port 2})))
    (is (= 5001 (core/parse-port {}))))
  (testing "the result is an int"
    (is (instance? Integer (core/parse-port {:server-port 5057})))))
