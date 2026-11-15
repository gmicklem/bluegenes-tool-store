(ns bluegenes-tool-store.package-test
  "Exercises tar.gz extraction (commons-compress) and the tools config file
  handling without touching the network."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [bluegenes-tool-store.package :as pkg])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.zip GZIPOutputStream]
           [org.apache.commons.compress.archivers.tar TarArchiveEntry TarArchiveOutputStream]))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "tool-store-test" (make-array FileAttribute 0))))

(defn- write-tgz!
  "Write a gzipped tar of `entries` (a map of path to string content) to `file`."
  [file entries]
  (with-open [tar (TarArchiveOutputStream. (GZIPOutputStream. (io/output-stream file)))]
    (doseq [[path content] entries
            :let [bytes (.getBytes ^String content "UTF-8")
                  entry (TarArchiveEntry. ^String path)]]
      (.setSize entry (count bytes))
      (.putArchiveEntry tar entry)
      (.write tar bytes)
      (.closeArchiveEntry tar))))

(deftest download-package-tgz-test
  (let [dir     (temp-dir)
        tgz     (io/file dir "pkg.tgz")
        target  (io/file dir "tools")
        package {:uri (str (.toURI tgz)) :name "@scope/some-tool" :version "1.0.0"}]
    (write-tgz! tgz {"package/package.json"  "{\"name\":\"@scope/some-tool\"}"
                     "package/dist/bundle.js" "console.log('hi');"})
    (testing "extracts entries and strips the leading package/ directory"
      (is (= package (#'pkg/download-package-tgz package target)))
      (is (= "console.log('hi');"
             (slurp (io/file target "@scope/some-tool" "dist" "bundle.js"))))
      (is (.exists (io/file target "@scope/some-tool" "package.json"))))))

(deftest install-config-and-uninstall-test
  (let [dir    (temp-dir)
        config (io/file dir "tools.edn")
        target (io/file dir "tools")
        tool   (io/file target "some-tool")]
    (spit config (pr-str {:tools {}}))
    (io/make-parents (io/file tool "dist" "bundle.js"))
    (spit (io/file tool "dist" "bundle.js") "x")
    (#'pkg/add-package-to-config {:name "some-tool" :version "2.0.0"} config)
    (is (= {:tools {"some-tool" "2.0.0"}} (edn/read-string (slurp config))))
    (pkg/uninstall-package config target "some-tool")
    (is (= {:tools {}} (edn/read-string (slurp config))))
    (is (not (.exists tool)))))
