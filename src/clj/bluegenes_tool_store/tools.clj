(ns bluegenes-tool-store.tools
  (:require [cheshire.core :as cheshire]
            [config.core :refer [env]]
            [ring.util.http-response :as response]
            [clojure.java.io :as io]
            [taoensso.timbre :as timbre :refer [error infof errorf warnf]]
            [bluegenes-tool-store.package :as pkg]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [clj-http.client :as client])
  (:import [java.util.concurrent.locks ReentrantLock]))

(def tools-path
  "Path to the tools directory containing the config file and packages."
  (io/file (or (:bluegenes-tool-path env) "./tools")))

(def tools-config
  "Path to the tools config file."
  (io/file tools-path "tools.edn"))

(def npm-search-url
  "NPM registry search for packages with the bluegenes tool keyword."
  (str "https://registry.npmjs.org/-/v1/search"
       "?text=keywords:bluegenes-intermine-tool&size=250"))

(def official-scope-prefix
  "Only packages in this npm scope are installed automatically. The registry
  search ignores a `scope:` qualifier, so this is enforced on the results; without
  it anybody could publish a package with the keyword and have it installed."
  "@intermine/")

(defn parse-npm-search
  "Parse the body of an NPM registry search response into a vector of results,
  each a map with a `:package` (`:name`, `:version`, `:description`, ...).
  Only official (`@intermine/`) packages with a valid npm name are kept. Throws on a malformed body."
  [body]
  (let [objects (:objects (cheshire/parse-string body true))]
    (when-not (sequential? objects)
      (throw (ex-info "NPM search response has no `objects` list." {:body-start (some-> body (subs 0 (min 200 (count body))))})))
    (when-not (every? #(string? (get-in % [:package :name])) objects)
      (throw (ex-info "NPM search response contains an entry without a package name." {})))
    (filterv #(let [n (get-in % [:package :name])]
                (and (string/starts-with? n official-scope-prefix)
                     ;; The prefix alone admits `@intermine/../../x`.
                     (pkg/valid-package-name? n)))
             objects)))

(defn get-all-npm-tools
  "Send an HTTP request to the NPM registry search API to get a list of
  bluegenes tools. On any failure, logs an error and returns an empty list."
  []
  (try
    (let [{:keys [status body]} (client/get npm-search-url {:throw-exceptions false})]
      (when-not (= 200 status)
        (throw (ex-info (format "unexpected HTTP status %s" status) {:status status})))
      (parse-npm-search body))
    (catch Exception e
      (errorf "Failed to acquire list of bluegenes-intermine-tool from the NPM registry (%s): %s"
              npm-search-url (.getMessage e))
      [])))

(declare package-operation)
(defn install-all-npm-tools
  "Install all bluegenes tools from the NPM registry."
  []
  (package-operation :install (mapv (comp :name :package) (get-all-npm-tools))))

(defn initialise-tools
  "Checks for tools config and initialises one if it doesn't exist."
  []
  (when-not (.exists tools-config)
    (infof "Tool config %s does not exist. It will automatically be created and all bluegenes tools will be installed." tools-config)
    (io/make-parents tools-config)
    (spit tools-config (pr-str {:tools {}}))
    (future (install-all-npm-tools))
    nil))

(defn force-initialise-tools
  "Forcibly initialises the tool config, installing the latest version of all
  official tools regardless of what is currently installed."
  []
  (when-not (.exists tools-config)
    (io/make-parents tools-config)
    (spit tools-config (pr-str {:tools {}})))
  (future (install-all-npm-tools))
  nil)

(defn installed-tools-list
  "Return a list of the installed tools in the tools config file."
  []
  (try
    (let [config (edn/read-string (slurp tools-config))]
      (keys (:tools config)))
    (catch Exception _
      (errorf "Tool config %s is missing. Please clear the tools directory at %s and restart BlueGenes." tools-config tools-path))))

(defn parse-tool
  "check tool folder for config and other relevant files and return as
  a map of useful info. This is used client-side by the browser to
  load tools relevant for a given report page.
  A valid tool needs to have the following:
  - package.json
  - config.json
  - dist/bundle.js"
  [tool-name]
  (try
    (when-let [msg (pkg/package-name-error tool-name)]
      (throw (ex-info msg {:tool-name tool-name})))
    (let [path (io/file tools-path tool-name)
          ;; this is the bluegenes-specific config.
          bluegenes-config-path (io/file path "config.json")
          config (cheshire/parse-string (slurp bluegenes-config-path) true)
          ;;this is the default npm package file
          package-path (io/file path "package.json")
          package (cheshire/parse-string (slurp package-path) true)
          ;;optional preview image for each tool.
          preview-image "preview.png"
          preview-path (io/file path preview-image)
          ;; Bundle for running the tool.
          bundle-path (io/file path "dist" "bundle.js")]
      (when-not (.exists bundle-path)
        (warnf "The bluegenes tool bundle file %s is missing, causing the tool to not work in bluegenes. It's likely that the bundle file has not been included in the npm package." bundle-path))
      ;; so many naming rules that conflict - we need three names.
      ;; npm requires kebab-case bluegenes-tool-protvista
      ;; but js vars forbid kebab case bluegenesToolProtvista
      ;; humans want something with spaces "Protein viewer"
      ;; this is terminally incompatible, hence three names. Argh.
      {:names {:human (get-in config [:toolName :human])
               :cljs (get-in config [:toolName :cljs])
               :npm (get-in package [:name])}
       :config config
       :package (select-keys package [:description :license :homepage :name :author :version])
        ;; return image path if it exists, or false otherwise.
       :hasimage (if (.exists preview-path)
                   (.getAbsolutePath (io/file "/tools" tool-name preview-image))
                   false)})
    (catch Exception e
      (warnf "An error occured when parsing tool %s: %s" tool-name (.getMessage e)))))

(defn tools-list-res
  "Create response containing the list of tools along with their parsed data."
  []
  (response/ok
    {:tools (remove nil? ; Faulty tools will return nil.
                    (map parse-tool (installed-tools-list)))}))

(defn package-names-error
  "Returns a message if `package+` (a package name or a sequential collection
  of names) contains anything that is not an acceptable npm package name, else
  nil. Nothing touches the filesystem before this check."
  [package+]
  (cond
    (string? package+)     (pkg/package-name-error package+)
    (sequential? package+)
    (some pkg/package-name-error package+)
    :else "Please include your package name(s) as a string or a list of strings."))

(let [lock       (ReentrantLock.)
      install    (partial pkg/install-package   tools-config tools-path)
      uninstall  (partial pkg/uninstall-package tools-config tools-path)]
  (defn package-operation
    "Perform a package install or uninstall `operation` on `package+` which can
    be a single package name or a collection of multiple. Returns an error
    response if a different package-operation is already in progress.
    On success it will return a response with the new tool list."
    [operation package+]
    (if-let [msg (package-names-error package+)]
      (response/bad-request {:error msg})
      (if (.tryLock lock)
      (try
        (case operation
          :install (if (coll? package+)
                     (doseq [package-name package+]
                       (install package-name))
                     (install package+))
          :uninstall (if (coll? package+)
                       (doseq [package-name package+]
                         (uninstall package-name))
                       (uninstall package+)))
        (tools-list-res)
        (catch Exception e
          (error e)
          (if (pkg/invalid-package-name? e)
            (response/bad-request {:error (.getMessage e)})
            (response/bad-gateway {:error (.getMessage e)})))
        (finally
          (.unlock lock)))
      (response/service-unavailable {:error "A tool operation is already in progress. Please try again later."})))))

(defn verify-package-params
  "Helper to wrap API functions to check for validity of package parameters."
  [f]
  (fn [{:keys [params] :as req}]
    (if (or (contains? params :package)
            (contains? params :packages))
      (if-let [msg (package-names-error (or (:package params) (:packages params)))]
        (response/bad-request {:error msg})
        (f req))
      (response/bad-request {:error "Please include your package name(s) in the package or packages parameter."}))))

;;;;;;;;;;;;;;;;;;;;;;;;
;; Tool API Endpoints ;;
;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-all-tools
  "Return list of tools as a REST response to our GET."
  []
  (tools-list-res))

(defn get-tools-path
  "Respond with tool path."
  []
  (response/ok {:path (.getAbsolutePath tools-path)}))

(def install-tool
  "Takes a request to install one or more tools."
  (verify-package-params
    (fn [{{:keys [package packages]} :params}]
      (package-operation :install (or package packages)))))

(def uninstall-tool
  "Takes a request to uninstall a tool."
  (verify-package-params
    (fn [{{:keys [package packages]} :params}]
      (package-operation :uninstall (or package packages)))))

(def update-tools
  "Takes a request to update a list of tools."
  (verify-package-params
    (fn [{{:keys [package packages]} :params}]
      (package-operation :install (or package packages)))))

;;;;;;;;;;;;;;;
;; CLI Usage ;;
;;;;;;;;;;;;;;;

(defn -main
  "This main function is for managing tools from the command line via the
  leiningen `lein tools` alias."
  [cmd & args]
  (timbre/set-level! :info)
  (case cmd
    "init"      (force-initialise-tools)
    "install"   (package-operation :install args)
    "uninstall" (package-operation :uninstall args)
    (println "Install BlueGenes tools from the command line.

USAGE: lein tools [ARGS...]

ARGS:
init                            - Initialise tools config and install all official tools
install package [package ...]   - Install one or more tools
uninstall package [package ...] - Uninstall one or more tools")))
