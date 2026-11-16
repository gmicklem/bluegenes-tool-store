(ns bluegenes-tool-store.package-test
  "Exercises tar.gz extraction (commons-compress) and the tools config file
  handling without touching the network."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [bluegenes-tool-store.package :as pkg])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.nio.file Files]
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

;; The registry is never contacted: `fetch-tarball!` is replaced by a copy of
;; a local file, and the package data carries the digests a registry would.

(defn- hex [^bytes bs] (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn- sha1-hex [file]
  (hex (.digest (java.security.MessageDigest/getInstance "SHA-1") (Files/readAllBytes (.toPath (io/file file))))))

(defn- sri [algo jca ^bytes content]
  (str algo "-" (.encodeToString (java.util.Base64/getEncoder)
                                 (.digest (java.security.MessageDigest/getInstance jca) content))))

(defn- package-for
  "Package data as `get-package-tgz` would return it for `tgz`."
  [tgz name]
  {:uri (str "https://registry.npmjs.org/" name "/-/pkg-1.0.0.tgz")
   :name name :version "1.0.0"
   :integrity (sri "sha512" "SHA-512" (Files/readAllBytes (.toPath (io/file tgz))))
   :shasum (sha1-hex tgz)})

(defn- download [package target tgz]
  (with-redefs [pkg/fetch-tarball! (fn [_ dest] (io/copy (io/file tgz) (io/file dest)))]
    (#'pkg/download-package-tgz package target)))

(deftest download-package-tgz-test
  (let [dir     (temp-dir)
        tgz     (io/file dir "pkg.tgz")
        target  (io/file dir "tools")
        _       (write-tgz! tgz {"package/package.json"  "{\"name\":\"@scope/some-tool\"}"
                                 "package/dist/bundle.js" "console.log('hi');"})
        package (package-for tgz "@scope/some-tool")]
    (testing "extracts entries and strips the leading package/ directory"
      (is (= package (download package target tgz)))
      (is (= "console.log('hi');"
             (slurp (io/file target "@scope/some-tool" "dist" "bundle.js"))))
      (is (.exists (io/file target "@scope/some-tool" "package.json"))))))

(deftest download-package-tgz-zip-slip-test
  (doseq [bad ["package/../../evil.txt" "package/sub/../../../evil.txt"]]
    (testing (str "entry " bad " is rejected and nothing is written outside the package dir")
      (let [dir     (temp-dir)
            tgz     (io/file dir "pkg.tgz")
            target  (io/file dir "tools")
            _       (write-tgz! tgz {"package/package.json" "{}"
                                     bad                    "pwned"})
            package (package-for tgz "some-tool")]
        (let [e (try (download package target tgz)
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

(deftest tarball-url-error-test
  (testing "https URLs on the registry host are accepted"
    (is (nil? (pkg/tarball-url-error "https://registry.npmjs.org/@intermine/bluegenes-protvista/-/bluegenes-protvista-1.0.0.tgz")))
    (is (nil? (pkg/tarball-url-error "https://registry.npmjs.org:443/x/-/x-1.0.0.tgz")))
    (is (nil? (pkg/tarball-url-error "HTTPS://Registry.NPMJS.org/x/-/x-1.0.0.tgz")))
    (is (pkg/valid-tarball-url? "https://registry.npmjs.org/x/-/x-1.0.0.tgz")))
  (testing "everything else is rejected with a message"
    (doseq [url ["http://registry.npmjs.org/x/-/x-1.0.0.tgz"
                 "file:///etc/passwd"
                 "file://registry.npmjs.org/x.tgz"
                 "ftp://registry.npmjs.org/x.tgz"
                 "https://evil.example.com/x-1.0.0.tgz"
                 "https://registry.npmjs.org.evil.example.com/x.tgz"
                 "https://evil.example.com/registry.npmjs.org/x.tgz"
                 "https://registry.npmjs.org@evil.example.com/x.tgz"
                 "https://user:pw@registry.npmjs.org/x.tgz"
                 "https://registry.npmjs.org:8443/x.tgz"
                 "registry.npmjs.org/x/-/x-1.0.0.tgz"
                 "//registry.npmjs.org/x.tgz"
                 "/x/-/x-1.0.0.tgz"
                 "https://registry.npmjs.org/with space.tgz"
                 ""
                 nil
                 42]]
      (is (string? (pkg/tarball-url-error url)) (pr-str url))
      (is (not (pkg/valid-tarball-url? url)) (pr-str url)))))

(deftest integrity-test
  (let [content (.getBytes "hello tarball" "UTF-8")
        other   (.getBytes "hello tampered tarball" "UTF-8")
        sha1    (hex (.digest (java.security.MessageDigest/getInstance "SHA-1") content))]
    (testing "sha512 SRI"
      (let [dist {:integrity (sri "sha512" "SHA-512" content)}]
        (is (= "sha512" (pkg/verify-integrity! dist content)))
        (let [e (try (pkg/verify-integrity! dist other) nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e))
          (is (= :mismatch (:bluegenes-tool-store.package/integrity-error (ex-data e)))))))
    (testing "sha1 shasum, upper or lower case"
      (is (= "sha1" (pkg/verify-integrity! {:shasum sha1} content)))
      (is (= "sha1" (pkg/verify-integrity! {:shasum (.toUpperCase ^String sha1)} content)))
      (is (thrown? clojure.lang.ExceptionInfo (pkg/verify-integrity! {:shasum sha1} other))))
    (testing "integrity wins over shasum, the strongest SRI algorithm is used"
      (is (thrown? clojure.lang.ExceptionInfo
                   (pkg/verify-integrity! {:integrity (sri "sha512" "SHA-512" other) :shasum sha1} content)))
      (is (= "sha512" (pkg/verify-integrity!
                        {:integrity (str (sri "sha256" "SHA-256" other) " " (sri "sha512" "SHA-512" content))}
                        content))))
    (testing "a wrong digest is rejected for a different content length too"
      (is (thrown? clojure.lang.ExceptionInfo (pkg/verify-integrity! {:shasum (apply str (repeat 40 "0"))} content))))
    (testing "no usable digest is a refusal, not a pass"
      (doseq [dist [{} {:integrity "" :shasum nil} {:integrity "md5-AAAA"} {:integrity "sha512-!!!"} {:shasum "abc"}]]
        (let [e (try (pkg/verify-integrity! dist content) nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (= :missing (:bluegenes-tool-store.package/integrity-error (ex-data e))) (pr-str dist)))))
    (testing "files are digested as well"
      (let [f (io/file (temp-dir) "c.bin")]
        (io/copy content f)
        (is (= "sha512" (pkg/verify-integrity! {:integrity (sri "sha512" "SHA-512" content)} f)))
        (is (thrown? clojure.lang.ExceptionInfo
                     (pkg/verify-integrity! {:integrity (sri "sha512" "SHA-512" other)} f)))))))

(deftest size-guard-test
  (testing "copy-bounded! copies up to the limit"
    (let [out (ByteArrayOutputStream.)]
      (is (= 100000 (pkg/copy-bounded! (ByteArrayInputStream. (byte-array 100000)) out 100000 "download")))
      (is (= 100000 (.size out)))))
  (testing "copy-bounded! stops once the limit is exceeded"
    (let [out (ByteArrayOutputStream.)
          e   (try (pkg/copy-bounded! (ByteArrayInputStream. (byte-array 100001)) out 100000 "download") nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (= "download" (:bluegenes-tool-store.package/limit-exceeded (ex-data e))))
      (is (<= (.size out) 100000))))
  (testing "Content-Length is checked up front"
    (is (= 10 (pkg/check-content-length! "10" 10 "download")))
    (is (nil? (pkg/check-content-length! nil 10 "download")))
    (is (nil? (pkg/check-content-length! "garbage" 10 "download")))
    (is (thrown? clojure.lang.ExceptionInfo (pkg/check-content-length! "11" 10 "download")))
    (is (thrown? clojure.lang.ExceptionInfo (pkg/check-content-length! 11 10 "download"))))
  (testing "the download limit is 50 MB"
    (is (= (* 50 1024 1024) pkg/max-download-bytes))))

(deftest download-package-tgz-refusals-test
  (let [dir    (temp-dir)
        tgz    (io/file dir "pkg.tgz")
        target (io/file dir "tools")
        _      (write-tgz! tgz {"package/package.json" "{}" "package/a.js" "1"})
        good   (package-for tgz "some-tool")
        fetched (atom 0)
        run    (fn [package]
                 (with-redefs [pkg/fetch-tarball! (fn [_ dest] (swap! fetched inc) (io/copy tgz (io/file dest)))]
                   (try (#'pkg/download-package-tgz package target) nil
                        (catch clojure.lang.ExceptionInfo e e))))]
    (testing "a bad URL is refused before anything is fetched"
      (doseq [u [(str (.toURI tgz)) "http://registry.npmjs.org/x.tgz" "https://evil.example.com/x.tgz"]]
        (let [e (run (assoc good :uri u))]
          (is (some? (:bluegenes-tool-store.package/bad-tarball-url (ex-data e))) u)))
      (is (zero? @fetched))
      (is (not (.exists (io/file target "some-tool")))))
    (testing "an integrity mismatch refuses, extracts nothing and keeps an installed version"
      (.mkdirs (io/file target "some-tool"))
      (spit (io/file target "some-tool" "old.txt") "old")
      (let [e (run (assoc good :integrity (sri "sha512" "SHA-512" (.getBytes "other" "UTF-8")) :shasum nil))]
        (is (= :mismatch (:bluegenes-tool-store.package/integrity-error (ex-data e)))))
      (is (= "old" (slurp (io/file target "some-tool" "old.txt"))))
      (is (not (.exists (io/file target "some-tool" "package.json")))))
    (testing "a package without digests is refused"
      (is (= :missing (:bluegenes-tool-store.package/integrity-error
                        (ex-data (run (dissoc good :integrity :shasum)))))))
    (testing "a matching shasum alone is enough"
      (is (nil? (run (dissoc good :integrity))))
      (is (.exists (io/file target "some-tool" "package.json"))))))

(deftest extraction-limits-test
  (let [dir    (temp-dir)
        tgz    (io/file dir "pkg.tgz")
        target (io/file dir "tools")
        _      (write-tgz! tgz {"package/a.txt" "aaaa" "package/b.txt" "bbbb" "package/c.txt" "cccc"})
        pkg-data (package-for tgz "some-tool")
        run    (fn []
                 (try (download pkg-data target tgz) nil
                      (catch clojure.lang.ExceptionInfo e e)))]
    (testing "too many entries"
      (with-redefs [pkg/max-extracted-entries 2]
        (let [e (run)]
          (is (= "entries" (:bluegenes-tool-store.package/limit-exceeded (ex-data e)))))
        (is (not (.exists (io/file target "some-tool"))) "partial extraction is cleaned up")))
    (testing "too many extracted bytes"
      (with-redefs [pkg/max-extracted-bytes 10]
        (let [e (run)]
          (is (= "extracted size" (:bluegenes-tool-store.package/limit-exceeded (ex-data e)))))
        (is (not (.exists (io/file target "some-tool"))) "partial extraction is cleaned up")))
    (testing "exactly at the limits is fine"
      (with-redefs [pkg/max-extracted-bytes 12 pkg/max-extracted-entries 3]
        (is (nil? (run)))
        (is (= "cccc" (slurp (io/file target "some-tool" "c.txt"))))))))
