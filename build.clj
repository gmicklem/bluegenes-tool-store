(ns build
  "tools.build tasks for bluegenes-tool-store.

    clojure -T:build clean
    clojure -T:build jar       ; target/bluegenes-tool-store-<version>.jar (library jar, no AOT) plus pom
    clojure -T:build install   ; jar, then install it into ~/.m2
    clojure -T:build uber      ; target/bluegenes-tool-store.jar, standalone server (AOT, main class core)

  The library jar matches what `lein with-profile noaot jar` produced: the
  sources under src/clj plus config/defaults and resources at the jar root."
  (:require [clojure.java.io :as io]
            [clojure.tools.build.api :as b]))

(def lib 'org.intermine/bluegenes-tool-store)
(def version "0.3.0-SNAPSHOT")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def uber-file (format "target/%s.jar" (name lib)))

(defn- basis [] (b/create-basis {:project "deps.edn"}))

(defn- existing-paths
  "The project's :paths which exist on disk (resources/ may be absent)."
  [basis]
  (filterv #(.isDirectory (io/file %)) (:paths basis)))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (let [basis (basis)
        src-dirs (existing-paths basis)]
    (b/delete {:path class-dir})
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis basis
                  :src-dirs ["src/clj"]
                  :pom-data [[:description "Microservice which serves tools for use with BlueGene's Tool API"]
                             [:url "http://www.intermine.org"]
                             [:licenses
                              [:license
                               [:name "LGPL-2.1-only"]]]]})
    (b/copy-dir {:src-dirs src-dirs :target-dir class-dir})
    (b/jar {:class-dir class-dir :jar-file jar-file})
    (println "Created" jar-file)))

(defn install [_]
  (jar nil)
  (b/install {:basis (basis)
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir})
  (println "Installed" (str lib) version "into ~/.m2"))

(defn uber [_]
  (let [basis (basis)
        class-dir "target/uber-classes"]
    (b/delete {:path class-dir})
    (b/copy-dir {:src-dirs (existing-paths basis) :target-dir class-dir})
    (b/compile-clj {:basis basis
                    :ns-compile '[bluegenes-tool-store.core]
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main 'bluegenes-tool-store.core})
    (println "Created" uber-file)))
