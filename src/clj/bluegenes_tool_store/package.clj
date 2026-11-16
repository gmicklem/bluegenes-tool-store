(ns bluegenes-tool-store.package
  (:require [cheshire.core :as cheshire]
            [clj-http.client :as client]
            [taoensso.timbre :refer [error warnf]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.edn :as edn])
  (:import [java.nio.file FileVisitResult Files LinkOption Path SimpleFileVisitor]
           [java.util.zip GZIPInputStream]
           [org.apache.commons.compress.archivers.tar TarArchiveInputStream TarArchiveEntry]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Package name and path validation ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private package-name-re
  "npm's rules for new package names: optionally scoped (`@scope/name`), lower
  case, and each segment starts with a letter or digit (so no leading dot,
  underscore or dash)."
  #"^(@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*$")

(def ^:private max-package-name-length 214)

(def ^:private reserved-package-names
  "Names that would collide with files that live in the tools directory."
  #{"tools.edn"})

(defn package-name-error
  "Returns a message describing why `package-name` is not an acceptable npm
  package name, or nil when it is acceptable."
  [package-name]
  (cond
    (not (string? package-name))
    "Package name must be a string."

    (> (count package-name) max-package-name-length)
    (format "Package name is longer than %d characters." max-package-name-length)

    (not (re-matches package-name-re package-name))
    (format "Invalid package name `%s`. Names must be lower case, optionally scoped as `@scope/name`, and may only contain letters, digits, `.`, `_` and `-`, starting with a letter or digit."
            package-name)

    (string/includes? package-name "..")
    (format "Invalid package name `%s`: `..` is not allowed." package-name)

    (contains? reserved-package-names package-name)
    (format "Invalid package name `%s`: reserved." package-name)))

(defn valid-package-name?
  [package-name]
  (nil? (package-name-error package-name)))

(defn- validate-package-name!
  "Throws an `ex-info` with `::invalid-package-name` in its data if
  `package-name` is not acceptable."
  [package-name]
  (when-let [msg (package-name-error package-name)]
    (throw (ex-info msg {::invalid-package-name package-name}))))

(defn- real-path
  "Absolute, normalised path of `f` (a File or Path) with symlinks resolved.
  The path need not exist: the deepest existing ancestor is resolved with
  `toRealPath` and the remaining components are appended."
  ^Path [f]
  (let [p (.normalize (.toAbsolutePath (if (instance? Path f) ^Path f (.toPath (io/file f)))))]
    (loop [existing p
           rest-parts '()]
      (cond
        (Files/exists existing (make-array LinkOption 0))
        (reduce (fn [^Path acc part] (.resolve acc ^String part))
                (.toRealPath existing (make-array LinkOption 0))
                rest-parts)

        (nil? (.getParent existing))
        p

        :else
        (recur (.getParent existing)
               (cons (str (.getFileName existing)) rest-parts))))))

(defn inside-dir?
  "True when `child` is located strictly inside the directory `root`, compared
  component-wise on real paths. A path equal to `root`, a sibling that merely
  shares a name prefix (`tools-evil` next to `tools`), anything reached through
  `..` or a symlink that leaves `root`, and unrelated absolute paths are all
  rejected."
  [root child]
  (let [r (real-path root)
        c (real-path child)]
    (and (.startsWith c r) (not= c r))))

(defn package-dir-for
  "The directory of `package-name` inside `target`. Throws when the name is
  invalid or the resulting directory is not strictly inside `target`."
  [target package-name]
  (validate-package-name! package-name)
  (let [dir (io/file target package-name)]
    (when-not (inside-dir? target dir)
      (throw (ex-info (format "Refusing package `%s`: it resolves outside the tools directory." package-name)
                      {::invalid-package-name package-name})))
    dir))

(defn invalid-package-name?
  "True if `e` was thrown because of an unacceptable package name."
  [e]
  (contains? (ex-data e) ::invalid-package-name))

(defn- delete-dir
  "Safely delete a `package` directory and its contents, if it exists, is a
  directory, and is strictly inside the `target` directory. Symlinks inside it
  are deleted, never followed."
  [target package]
  (let [path (.toPath (io/file package))]
    (when (and (Files/isDirectory path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
               (inside-dir? target package))
      (Files/walkFileTree
        path
        (proxy [SimpleFileVisitor] []
          (visitFile [f _]
            (Files/delete f)
            FileVisitResult/CONTINUE)
          (postVisitDirectory [d _]
            (Files/delete d)
            FileVisitResult/CONTINUE))))))

;; Archive streaming code taken from:
;; https://github.com/Raynes/fs/blob/master/src/me/raynes/fs/compression.clj

(defn- tar-entries
  "Get a lazy-seq of entries in a tarfile."
  [^TarArchiveInputStream tin]
  (when-let [entry (.getNextTarEntry tin)]
    (cons entry (lazy-seq (tar-entries tin)))))

(defn- safe-entry-file
  "Returns the file for `entry-path` inside `package-dir`. Throws if the
  destination is not strictly inside `package-dir`, which stops archives with
  `../` or absolute entry names from writing elsewhere (zip-slip)."
  [package-dir entry-path]
  (let [dest (io/file package-dir entry-path)]
    (when-not (inside-dir? package-dir dest)
      (throw (ex-info (format "Refusing to extract archive entry `%s`: it resolves outside the target directory `%s`."
                              entry-path package-dir)
                      {::unsafe-entry entry-path :package-dir (str package-dir)})))
    dest))

(defn- download-package-tgz
  "Downloads and decompresses a tgz file at `uri` into the folder `target`.
  Removes any leading 'package/' in the tar archive's entry paths. This is
  because it's prepended automatically by npmjs.org and we don't want it."
  [{:keys [uri name] :as package-data} target]
  (let [package-dir (package-dir-for target name)]
    (try
      (delete-dir target package-dir)
      (with-open [in (TarArchiveInputStream. (GZIPInputStream. (io/input-stream uri)))]
        (doseq [^TarArchiveEntry entry (tar-entries in)
                 :when (not (.isDirectory entry))
                 :let [entry-path (string/replace-first (.getName entry) #"^package/" "")
                       output-file (safe-entry-file package-dir entry-path)]]
          (io/make-parents output-file)
          (io/copy in output-file)))
      package-data
      (catch Exception e
        (error e)
        ;; Unsafe archive: remove anything already extracted and report it as is.
        (when (::unsafe-entry (ex-data e))
          (delete-dir target package-dir)
          (throw e))
        (throw
          (ex-info (format "Failed to download NPM archive `%s`. This is likely due to a network error." uri)
                   {:uri uri :name name :target target}))))))

(defn- get-package-tgz
  "Uses the NPMJS Registry API to get a link to the tarball of the latest
  version of `package-name`."
  [package-name]
  (validate-package-name! package-name)
  (let [registry-url (str "https://registry.npmjs.org/" package-name)]
    (try
      (let [res (-> registry-url
                    client/get
                    :body
                    (cheshire/parse-string true))
            latest-tag (get-in res [:dist-tags :latest])]
        {:uri (get-in res [:versions (keyword latest-tag) :dist :tarball])
         :name package-name
         :version latest-tag})
      (catch Exception e
        (error e)
        (throw
          (ex-info (format "Failed to GET `%s`. This is likely due to a network error." registry-url)
                   {:package-name package-name :uri registry-url}))))))

(defn- pp-str
  "Naive implementation of a pretty printer.
  Needs to be adapted if you change the tool config."
  [tools]
  ;; Yes, it's modifying data with regex. We're just replacing whitespace with
  ;; more whitespace, so it can't break anything. Indentation is hardcoded so
  ;; make sure you update this if you change the structure of the tools file.
  (string/replace (pr-str tools) ", " "\n         "))

(defn- add-package-to-config
  "Adds the package name and version to the tool `config`."
  [{:keys [name version]} config]
  (let [tools  (try (edn/read-string (slurp config))
                 (catch Exception _
                   (warnf "Tool config %s is missing or invalid. It will be recreated with %s installed." config name)))
        tools' (assoc-in tools [:tools name] version)]
    (spit config (pp-str tools'))
    tools'))

(defn- remove-package-from-config
  "Removes `package-name` from the tool `config`."
  [package-name config]
  (let [tools  (try (edn/read-string (slurp config))
                 (catch Exception _
                   (warnf "Tool config %s is missing or invalid. An empty one will be created." config)))
        tools' (update tools :tools dissoc package-name)]
    (spit config (pp-str tools'))
    tools'))

(defn install-package
  "Installs a package using its `package-name` into `target-dir` and updates
  `tools-config`. Do not call this function directly; use
  `bluegenes-tool-store.tools/package-operation` instead."
  [tools-config target-dir package-name]
  (some-> package-name
          get-package-tgz
          (download-package-tgz target-dir)
          (add-package-to-config tools-config)))

(defn uninstall-package
  "Uninstalls a package using its `package-name` from `target-dir` and updates
  `tools-config`. Do not call this function directly; use
  `bluegenes-tool-store.tools/package-operation` instead."
  [tools-config target-dir package-name]
  (let [package-dir (package-dir-for target-dir package-name)]
    (delete-dir target-dir package-dir)
    (remove-package-from-config package-name tools-config)))
