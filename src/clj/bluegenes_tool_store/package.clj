(ns bluegenes-tool-store.package
  (:require [cheshire.core :as cheshire]
            [clj-http.client :as client]
            [taoensso.timbre :refer [error infof warnf]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.edn :as edn])
  (:import [java.nio.file FileVisitResult Files LinkOption Path SimpleFileVisitor]
           [java.nio.file.attribute FileAttribute]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tarball URL, integrity and size restrictions ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def registry-host
  "The only host tarballs are downloaded from. The registry URL is hard-coded,
  so this is the one host it can legitimately point to."
  "registry.npmjs.org")

(def max-download-bytes
  "Upper bound for the size of a downloaded (compressed) tarball: 50 MB."
  (* 50 1024 1024))

(def max-extracted-bytes
  "Upper bound for the total size of everything extracted from one tarball:
  200 MB. Guards against decompression bombs."
  (* 200 1024 1024))

(def max-extracted-entries
  "Upper bound for the number of entries (files and directories) in one tarball."
  10000)

(defn tarball-url-error
  "Returns a message describing why `url` is not an acceptable tarball URL, or
  nil when it is. Only `https:` URLs on `registry-host` (default port, no
  credentials) are accepted; `file:`, `http:`, scheme-less URLs and any other
  host are rejected."
  [url]
  (if-not (string? url)
    "The registry did not provide a tarball URL for the package."
    (let [uri (try (java.net.URI. url) (catch Exception _ nil))]
      (cond
        (nil? uri)
        (format "Refusing tarball URL `%s`: it is not a valid URL." url)

        (not= "https" (some-> (.getScheme uri) string/lower-case))
        (format "Refusing tarball URL `%s`: only https URLs are accepted." url)

        (some? (.getUserInfo uri))
        (format "Refusing tarball URL `%s`: credentials in URLs are not accepted." url)

        (not= registry-host (some-> (.getHost uri) string/lower-case))
        (format "Refusing tarball URL `%s`: only tarballs hosted on %s are accepted." url registry-host)

        (not (contains? #{-1 443} (.getPort uri)))
        (format "Refusing tarball URL `%s`: non-default ports are not accepted." url)))))

(defn valid-tarball-url?
  [url]
  (nil? (tarball-url-error url)))

(defn- validate-tarball-url!
  [url]
  (when-let [msg (tarball-url-error url)]
    (throw (ex-info msg {::bad-tarball-url url}))))

(def ^:private sri-algorithms
  "SRI algorithm names in order of preference, with the JCA names."
  [["sha512" "SHA-512"] ["sha384" "SHA-384"] ["sha256" "SHA-256"]])

(defn- hex->bytes ^bytes [^String s]
  (byte-array (map #(unchecked-byte (Integer/parseInt (apply str %) 16))
                   (partition 2 s))))

(defn expected-digests
  "Works out which digest to check a tarball against from the registry's
  `dist` map. Prefers the `integrity` field (Subresource Integrity, e.g.
  `sha512-<base64>`; the strongest algorithm present is used) and falls back
  to the SHA-1 `shasum`. Returns `{:algorithm \"sha512\" :jca \"SHA-512\"
  :digests [<byte-array> ...]}`, or nil when neither field is usable."
  [{:keys [integrity shasum]}]
  (or
    (when (string? integrity)
      (let [parsed (for [token (string/split (string/trim integrity) #"\s+")
                         :let  [[_ algo b64] (re-matches #"([a-z0-9]+)-([A-Za-z0-9+/]+={0,2})(?:\?.*)?" token)]
                         :when algo]
                     [algo (try (.decode (java.util.Base64/getDecoder) ^String b64)
                                (catch IllegalArgumentException _ nil))])]
        (some (fn [[algo jca]]
                (let [ds (keep (fn [[a d]] (when (and (= a algo) d) d)) parsed)]
                  (when (seq ds)
                    {:algorithm algo :jca jca :digests (vec ds)})))
              sri-algorithms)))
    (when (and (string? shasum) (re-matches #"[0-9a-fA-F]{40}" shasum))
      {:algorithm "sha1" :jca "SHA-1" :digests [(hex->bytes shasum)]})))

(defn- digest-bytes
  ^bytes [^String jca ^bytes content]
  (.digest (java.security.MessageDigest/getInstance jca) content))

(defn- digest-file
  ^bytes [^String jca file]
  (let [md (java.security.MessageDigest/getInstance jca)
        buf (byte-array 65536)]
    (with-open [in (io/input-stream file)]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.update md buf 0 n)
            (recur)))))
    (.digest md)))

(defn integrity-matches?
  "True when `actual` (a digest byte array) equals any of the expected
  digests in `expected` (from `expected-digests`)."
  [expected ^bytes actual]
  (boolean (some #(java.security.MessageDigest/isEqual ^bytes % actual) (:digests expected))))

(defn verify-integrity!
  "Checks `content` (a byte array or a file) against the digest(s) in `dist`
  (a map with `:integrity` and/or `:shasum`). Logs the algorithm checked and
  returns it (e.g. `\"sha512\"`). Throws an `ex-info` with
  `::integrity-error` in its data when `dist` has no usable digest or the
  content does not match."
  [dist content]
  (let [{:keys [algorithm jca] :as expected} (expected-digests dist)]
    (when-not expected
      (throw (ex-info "Refusing to install: the registry provided no usable integrity (sha512/sha384/sha256) or shasum for the tarball."
                      {::integrity-error :missing})))
    (let [actual (if (bytes? content)
                   (digest-bytes jca content)
                   (digest-file jca content))]
      (when-not (integrity-matches? expected actual)
        (throw (ex-info (format "Refusing to install: the downloaded tarball failed its %s integrity check." algorithm)
                        {::integrity-error :mismatch :algorithm algorithm})))
      (infof "Tarball integrity verified (%s)." algorithm)
      algorithm)))

(defn- limit-error
  [what limit]
  (ex-info (format "Refusing to continue: %s exceeds the limit of %d bytes." what limit)
           {::limit-exceeded what :limit limit}))

(defn copy-bounded!
  "Copies `in` to `out`, throwing an `ex-info` with `::limit-exceeded` in its
  data as soon as more than `limit` bytes have been read. Returns the number
  of bytes copied."
  [^java.io.InputStream in ^java.io.OutputStream out limit what]
  (let [buf (byte-array 65536)]
    (loop [total 0]
      (let [n (.read in buf)]
        (if (neg? n)
          total
          (let [total' (+ total n)]
            (when (> total' limit)
              (throw (limit-error what limit)))
            (.write out buf 0 n)
            (recur total')))))))

(defn check-content-length!
  "Throws when the `Content-Length` header value `content-length` (a string,
  number or nil) announces more than `limit` bytes."
  [content-length limit what]
  (let [n (cond
            (number? content-length) content-length
            (string? content-length) (try (Long/parseLong (string/trim content-length))
                                          (catch NumberFormatException _ nil)))]
    (when (and n (> n limit))
      (throw (limit-error what limit)))
    n))

(defn- fetch-tarball!
  "Downloads `url` (an https registry URL, already validated) to `dest`, never
  following redirects and never writing more than `max-download-bytes`."
  [url dest]
  (let [res (client/get url {:as :stream
                             :redirect-strategy :none
                             :throw-exceptions false
                             :socket-timeout 60000
                             :connection-timeout 15000})]
    (try
      (when-not (= 200 (:status res))
        (throw (ex-info (format "Unexpected HTTP status %s from `%s`." (:status res) url)
                        {:uri url :status (:status res)})))
      (check-content-length! (get-in res [:headers "Content-Length"]) max-download-bytes "download")
      (with-open [out (io/output-stream dest)]
        (copy-bounded! (:body res) out max-download-bytes "download"))
      (finally
        (when-let [body ^java.io.Closeable (:body res)] (.close body))))))

;; Archive streaming code taken from:
;; https://github.com/Raynes/fs/blob/master/src/me/raynes/fs/compression.clj

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

(defn- extract-tgz!
  "Extracts the tgz file `tgz` into `package-dir`, removing any leading
  'package/' in the entry paths. At most `max-extracted-entries` entries and
  `max-extracted-bytes` bytes in total are accepted."
  [tgz package-dir]
  (with-open [in (TarArchiveInputStream. (GZIPInputStream. (io/input-stream tgz)))]
    (loop [entries 0
           total   0]
      (when-let [^TarArchiveEntry entry (.getNextTarEntry in)]
        (when (>= entries max-extracted-entries)
          (throw (ex-info (format "Refusing to continue: the archive has more than %d entries." max-extracted-entries)
                          {::limit-exceeded "entries" :limit max-extracted-entries})))
        (if (.isDirectory entry)
          (recur (inc entries) total)
          (let [entry-path  (string/replace-first (.getName entry) #"^package/" "")
                output-file (safe-entry-file package-dir entry-path)]
            (io/make-parents output-file)
            (let [n (with-open [out (io/output-stream output-file)]
                      (copy-bounded! in out (- max-extracted-bytes total) "extracted size"))]
              (recur (inc entries) (+ total n)))))))))

(defn- safety-error?
  "True when `e` is one of the deliberate refusals that must be reported as is
  rather than as a network error."
  [e]
  (let [d (ex-data e)]
    (boolean (or (::unsafe-entry d) (::limit-exceeded d) (::integrity-error d) (::bad-tarball-url d)))))

(defn- download-package-tgz
  "Downloads the tgz file at `uri` into a temporary file, checks it against
  the registry's `integrity`/`shasum` (`package-data`) and decompresses it
  into the folder `target`. The URL must be an https URL on the registry host,
  the download is limited to `max-download-bytes` and the extraction to
  `max-extracted-bytes`/`max-extracted-entries`. Removes any leading
  'package/' in the tar archive's entry paths. This is because it's prepended
  automatically by npmjs.org and we don't want it."
  [{:keys [uri name] :as package-data} target]
  (let [package-dir (package-dir-for target name)
        tmp         (Files/createTempFile "bluegenes-tool-" ".tgz" (make-array FileAttribute 0))]
    (try
      (validate-tarball-url! uri)
      (fetch-tarball! uri (.toFile tmp))
      ;; Nothing is extracted, and an installed version is kept, unless the
      ;; tarball verifies.
      (verify-integrity! package-data (.toFile tmp))
      (delete-dir target package-dir)
      (extract-tgz! (.toFile tmp) package-dir)
      package-data
      (catch Exception e
        (error e)
        ;; Unsafe or oversized archive: remove anything already extracted and report it as is.
        (when (safety-error? e)
          (when (or (::unsafe-entry (ex-data e)) (::limit-exceeded (ex-data e)))
            (delete-dir target package-dir))
          (throw e))
        (throw
          (ex-info (format "Failed to download NPM archive `%s`. This is likely due to a network error." uri)
                   {:uri uri :name name :target target})))
      (finally
        (Files/deleteIfExists tmp)))))

(defn- get-package-tgz
  "Uses the NPMJS Registry API to get a link to the tarball of the latest
  version of `package-name`, along with the integrity data the tarball must
  match. Throws if the link is not an https URL on the registry host."
  [package-name]
  (validate-package-name! package-name)
  (let [registry-url (str "https://" registry-host "/" package-name)
        {:keys [latest-tag dist]}
        (try
          (let [res (-> registry-url
                        client/get
                        :body
                        (cheshire/parse-string true))
                latest-tag (get-in res [:dist-tags :latest])]
            {:latest-tag latest-tag
             :dist       (get-in res [:versions (keyword latest-tag) :dist])})
          (catch Exception e
            (error e)
            (throw
              (ex-info (format "Failed to GET `%s`. This is likely due to a network error." registry-url)
                       {:package-name package-name :uri registry-url}))))
        uri (:tarball dist)]
    (validate-tarball-url! uri)
    {:uri       uri
     :name      package-name
     :version   latest-tag
     :integrity (:integrity dist)
     :shasum    (:shasum dist)}))

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
