(ns bluegenes-tool-store.tools-test
  "Tests for reading installed tools and the HTTP handler. No network access."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cheshire.core :as cheshire]
            [bluegenes-tool-store.auth :as auth]
            [bluegenes-tool-store.core :as core]
            [bluegenes-tool-store.package :as pkg]
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

(deftest package-endpoints-reject-bad-names-test
  (let [ops (atom [])]
    (with-redefs [auth/check-priv (fn [handler req] (handler req))
                  pkg/install-package   (fn [& args] (swap! ops conj [:install args]) nil)
                  pkg/uninstall-package (fn [& args] (swap! ops conj [:uninstall args]) nil)]
      (doseq [uri ["/api/tools/install" "/api/tools/uninstall" "/api/tools/update"]
              bad ["../x" "-flag" "@scope/.." "/etc/passwd" "Upper" (apply str (repeat 215 "a"))
                   ["@intermine/ok" "../x"] 42 nil]]
        (testing (str uri " rejects " (pr-str bad))
          (let [res (core/handler {:request-method :post :uri uri :headers {}
                                   :body-params {:package bad}})]
            (is (= 400 (:status res)))
            (is (re-find #"error" (let [b (:body res)] (if (map? b) (pr-str b) (slurp b)))))))
        (testing (str uri " rejects packages " (pr-str bad))
          (is (= 400 (:status (core/handler {:request-method :post :uri uri :headers {}
                                             :body-params {:packages bad}}))))))
      (is (empty? @ops) "nothing was installed or uninstalled")
      (testing "the missing parameter case is still a 400"
        (is (= 400 (:status (core/handler {:request-method :post :uri "/api/tools/install"
                                           :headers {} :body-params {}})))))))
  (testing "package-operation itself refuses invalid names without touching the filesystem"
    (with-redefs [pkg/install-package (fn [& _] (throw (ex-info "should not run" {})))]
      (is (= 400 (:status (tools/package-operation :install "../x"))))
      (is (= 400 (:status (tools/package-operation :uninstall ["ok" "@s/.."])))))))

(deftest parse-tool-rejects-unsafe-names-test
  (let [dir (temp-dir)]
    ;; Valid tool files one level above the tools dir: reachable only via "..".
    (spit (io/file dir "config.json") "{\"toolName\":{\"human\":\"x\"}}")
    (spit (io/file dir "package.json") "{\"name\":\"x\"}")
    (.mkdirs (io/file dir "tools"))
    (with-redefs [tools/tools-path (io/file dir "tools")]
      (is (nil? (tools/parse-tool "..")))
      (is (nil? (tools/parse-tool "../"))))))

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

(def ^:private npm-search-fixture
  "Trimmed response of https://registry.npmjs.org/-/v1/search?text=keywords:bluegenes-intermine-tool"
  (cheshire/generate-string
    {:objects [{:package {:name "@intermine/bluegenes-protvista" :version "2.0.0"
                          :description "Protein viewer" :keywords ["bluegenes-intermine-tool"]}
                :score {:final 0.5}}
               {:package {:name "@intermine/bluegenes-msa-viewer" :version "2.1.1"
                          :description "MSA"}}
               {:package {:name "evil-unscoped-package" :version "1.0.0"
                          :keywords ["bluegenes-intermine-tool"]}}
               {:package {:name "@someone-else/bluegenes-thing" :version "1.0.0"}}]
     :total 4 :time "2026-11-15T00:00:00.000Z"}))

(deftest parse-npm-search-test
  (testing "official-prefix names that are not valid npm names are dropped"
    (is (= ["@intermine/ok"]
           (map (comp :name :package)
                (tools/parse-npm-search
                  (cheshire/generate-string
                    {:objects [{:package {:name "@intermine/ok"}}
                               {:package {:name "@intermine/../../x"}}
                               {:package {:name "@intermine/-flag"}}
                               {:package {:name "@intermine/UPPER"}}]}))))))
  (let [results (tools/parse-npm-search npm-search-fixture)]
    (testing "official packages are kept with name, version and description"
      (is (= ["@intermine/bluegenes-protvista" "@intermine/bluegenes-msa-viewer"]
             (map (comp :name :package) results)))
      (is (= {:name "@intermine/bluegenes-protvista" :version "2.0.0"
              :description "Protein viewer" :keywords ["bluegenes-intermine-tool"]}
             (:package (first results)))))
    (testing "packages outside the @intermine scope are ignored"
      (is (not-any? #(= "evil-unscoped-package" (get-in % [:package :name])) results))))
  (testing "an empty result list is fine"
    (is (= [] (tools/parse-npm-search "{\"objects\":[]}"))))
  (testing "malformed responses throw"
    (is (thrown? Exception (tools/parse-npm-search "not json")))
    (is (thrown? Exception (tools/parse-npm-search "{\"results\":[]}")))
    (is (thrown? Exception (tools/parse-npm-search "{\"objects\":[{\"package\":{}}]}")))))

(deftest get-all-npm-tools-test
  (testing "queries the npm registry and returns parsed results"
    (let [url (atom nil)]
      (with-redefs [clj-http.client/get (fn [u _] (reset! url u) {:status 200 :body npm-search-fixture})]
        (is (= 2 (count (tools/get-all-npm-tools))))
        (is (re-find #"^https://registry\.npmjs\.org/-/v1/search\?text=keywords:bluegenes-intermine-tool" @url)))))
  (testing "a non-200 response is logged as an error and yields an empty list"
    (with-redefs [clj-http.client/get (fn [_ _] {:status 503 :body "down"})
                  taoensso.timbre/*config* (assoc taoensso.timbre/*config* :level :fatal)]
      (is (= [] (tools/get-all-npm-tools)))))
  (testing "a malformed response yields an empty list"
    (with-redefs [clj-http.client/get (fn [_ _] {:status 200 :body "<html>"})
                  taoensso.timbre/*config* (assoc taoensso.timbre/*config* :level :fatal)]
      (is (= [] (tools/get-all-npm-tools)))))
  (testing "a network exception yields an empty list"
    (with-redefs [clj-http.client/get (fn [_ _] (throw (java.net.UnknownHostException. "x")))
                  taoensso.timbre/*config* (assoc taoensso.timbre/*config* :level :fatal)]
      (is (= [] (tools/get-all-npm-tools))))))
