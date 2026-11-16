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

(deftest download-package-tgz-zip-slip-test
  (doseq [bad ["package/../../evil.txt" "package/sub/../../../evil.txt"]]
    (testing (str "entry " bad " is rejected and nothing is written outside the package dir")
      (let [dir     (temp-dir)
            tgz     (io/file dir "pkg.tgz")
            target  (io/file dir "tools")
            package {:uri (str (.toURI tgz)) :name "some-tool" :version "1.0.0"}]
        (write-tgz! tgz {"package/package.json" "{}"
                         bad                    "pwned"})
        (let [e (try (#'pkg/download-package-tgz package target)
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e))
          (is (re-find #"outside the target directory" (.getMessage e)))
          (is (= (subs bad (count "package/"))
                 (:bluegenes-tool-store.package/unsafe-entry (ex-data e)))))
        (is (not (.exists (io/file dir "evil.txt"))))
        (is (not (.exists (io/file target "evil.txt"))))
        (is (not (.exists (io/file target "some-tool")))
            "partial extraction is cleaned up"))))
  (testing "safe-entry-file accepts nested paths and rejects the directory itself"
    (let [d (temp-dir)]
      (is (= (io/file d "a/b.js") (#'pkg/safe-entry-file d "a/b.js")))
      (is (thrown? clojure.lang.ExceptionInfo (#'pkg/safe-entry-file d "a/../..")))
      (is (thrown? clojure.lang.ExceptionInfo (#'pkg/safe-entry-file d ".")))
      (is (thrown? clojure.lang.ExceptionInfo (#'pkg/safe-entry-file d "../sibling/x"))))))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Path containment / name validation ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest inside-dir-test
  (let [dir   (temp-dir)
        tools (io/file dir "tools")]
    (.mkdirs tools)
    (.mkdirs (io/file dir "tools-evil"))
    (testing "children and nested paths are inside, even if they do not exist yet"
      (is (pkg/inside-dir? tools (io/file tools "a")))
      (is (pkg/inside-dir? tools (io/file tools "@scope/name/dist/bundle.js"))))
    (testing "the root itself is not inside"
      (is (not (pkg/inside-dir? tools tools)))
      (is (not (pkg/inside-dir? tools (io/file tools "a/.."))))
      (is (not (pkg/inside-dir? tools (io/file tools ".")))))
    (testing "a sibling whose name starts with the root's name is outside (string-prefix bug)"
      (is (not (pkg/inside-dir? tools (io/file dir "tools-evil")))))
    (testing "parent directory traversal is outside"
      (is (not (pkg/inside-dir? tools (io/file tools "../x"))))
      (is (not (pkg/inside-dir? tools (io/file tools "a/../../x"))))
      (is (not (pkg/inside-dir? tools dir))))
    (testing "unrelated absolute paths are outside"
      (is (not (pkg/inside-dir? tools (io/file "/etc/passwd"))))
      (is (not (pkg/inside-dir? tools (io/file "/")))))
    (testing "a symlink inside root that points outside is outside"
      (Files/createSymbolicLink (.toPath (io/file tools "link"))
                                (.toPath (io/file dir "tools-evil"))
                                (make-array FileAttribute 0))
      (is (not (pkg/inside-dir? tools (io/file tools "link"))))
      (is (not (pkg/inside-dir? tools (io/file tools "link/x")))))))

(deftest package-name-validation-test
  (testing "valid names"
    (doseq [n ["some-tool" "@intermine/bluegenes-protvista" "a" "a.b_c-d" "@s/n" "tool2"
               (apply str (repeat 214 "a"))]]
      (is (nil? (pkg/package-name-error n)) n)
      (is (pkg/valid-package-name? n))))
  (testing "invalid names"
    (doseq [n ["../x" "x/../y" "/etc/passwd" "-flag" "--registry=http://evil" "@scope/.." "@scope/../x"
               "@../x" "@scope/-x" "@scope" "@scope/" "@/name" "@a/b/c" "a/b" "Upper" "@Scope/name"
               ".hidden" "_private" "@scope/.hidden" "@scope/_x" "a..b" "" " " "a b" "a\nb" "a\\b"
               "tools.edn" "@intermine/../../x"
               (apply str (repeat 215 "a")) nil 42 {:a 1} ["a"]]]
      (is (some? (pkg/package-name-error n)) (pr-str n))
      (is (not (pkg/valid-package-name? n))))))

(deftest package-dir-for-test
  (let [dir (temp-dir)]
    (is (= (io/file dir "@scope/name") (pkg/package-dir-for dir "@scope/name")))
    (is (= (io/file dir "name") (pkg/package-dir-for dir "name")))
    (doseq [n ["../x" "@scope/.." ".." "/abs" "tools.edn"]]
      (is (thrown? clojure.lang.ExceptionInfo (pkg/package-dir-for dir n)) n))))

(deftest delete-dir-test
  (let [dir     (temp-dir)
        tools   (io/file dir "tools")
        evil    (io/file dir "tools-evil")
        outside (io/file dir "outside")]
    (.mkdirs tools)
    (.mkdirs (io/file evil "sub"))
    (spit (io/file evil "sub/keep.txt") "keep")
    (.mkdirs outside)
    (spit (io/file outside "keep.txt") "keep")
    (spit (io/file tools "tools.edn") "{}")
    (testing "a sibling with a common name prefix is not deleted"
      (#'pkg/delete-dir tools evil)
      (is (.exists (io/file evil "sub/keep.txt"))))
    (testing "the root itself and its parent are not deleted"
      (#'pkg/delete-dir tools tools)
      (#'pkg/delete-dir tools dir)
      (is (.exists (io/file tools "tools.edn")))
      (is (.exists outside)))
    (testing "files (not directories) are not deleted"
      (#'pkg/delete-dir tools (io/file tools "tools.edn"))
      (is (.exists (io/file tools "tools.edn"))))
    (testing "a package directory is deleted, and symlinks inside are removed but not followed"
      (let [pkg-dir (io/file tools "@scope/name")]
        (.mkdirs (io/file pkg-dir "dist"))
        (spit (io/file pkg-dir "dist/bundle.js") "x")
        (Files/createSymbolicLink (.toPath (io/file pkg-dir "link")) (.toPath outside)
                                  (make-array FileAttribute 0))
        (#'pkg/delete-dir tools pkg-dir)
        (is (not (.exists pkg-dir)))
        (is (.exists (io/file outside "keep.txt")))))
    (testing "a package directory that is a symlink out of the tools dir is not deleted"
      (Files/createSymbolicLink (.toPath (io/file tools "linked")) (.toPath outside)
                                (make-array FileAttribute 0))
      (#'pkg/delete-dir tools (io/file tools "linked"))
      (is (.exists (io/file outside "keep.txt"))))))

(deftest unsafe-names-never-touch-filesystem-test
  (let [dir     (temp-dir)
        config  (io/file dir "tools.edn")
        target  (io/file dir "tools")
        victim  (io/file dir "victim")]
    (.mkdirs target)
    (.mkdirs victim)
    (spit (io/file victim "keep.txt") "keep")
    (spit config (pr-str {:tools {}}))
    (testing "uninstall refuses traversal names and leaves the config alone"
      (is (thrown? clojure.lang.ExceptionInfo (pkg/uninstall-package config target "../victim")))
      (is (thrown? clojure.lang.ExceptionInfo (pkg/uninstall-package config target "@scope/../../victim")))
      (is (.exists (io/file victim "keep.txt")))
      (is (= {:tools {}} (edn/read-string (slurp config)))))
    (testing "install refuses before any network access"
      (with-redefs [clj-http.client/get (fn [& _] (throw (ex-info "network used" {})))]
        (doseq [n ["../victim" "-flag" "@scope/.."]]
          (let [e (try (pkg/install-package config target n) nil
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (some? e))
            (is (pkg/invalid-package-name? e) n)))))))
