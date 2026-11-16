[![Clojars Project](https://img.shields.io/clojars/v/org.intermine/bluegenes-tool-store.svg)](https://clojars.org/org.intermine/bluegenes-tool-store)

# BlueGenes Tool Store

## Building with the Clojure CLI (deps.edn + tools.build)

The Clojure CLI is the preferred build tool; `project.clj` is kept for now so both work.
Keep the dependency lists in `deps.edn` and `project.clj` in sync.

    clojure -M:test                  # run the tests
    clojure -M -m bluegenes-tool-store.core   # start the server (add :dev or :prod aliases for local config)
    clojure -T:build jar             # target/bluegenes-tool-store-<version>.jar (+ pom), no AOT
    clojure -T:build install         # install the jar into ~/.m2
    clojure -T:build uber            # target/bluegenes-tool-store.jar, standalone server
    clojure -M -m bluegenes-tool-store.tools help   # tools CLI (replaces `lein tools`)

## Development server

    lein dev

## Production server

    lein prod

## Deploying to Clojars

    lein deploy

## Initialising tools

The first time you start *bluegenes-tool-store*, all [npmjs](https://www.npmjs.com/) packages under the scope *intermine* with the keyword *bluegenes-intermine-tool* will be downloaded and their tool installed (for further tool management, use the BlueGenes interface).

To replicate this behaviour in a project where *bluegenes-tool-store* is used as a dependency, you need to add the following function call to your initialisation code.

```clojure
  ;; Add the require to your ns declaration.
  (:require [bluegenes-tool-store.tools :as tools]))

    ;; In your initialisation code.
    (tools/initialise-tools)
```

## Tool management via CLI

Note: The tools CLI doesn't give very user-friendly feedback, and is only meant as a temporary solution for older InterMine instances which don't support tool management via the BlueGenes interface due to security reasons.

If you still wish to use the tools CLI, run the following command for a usage guide.

```
clojure -M -m bluegenes-tool-store.tools help
```

If *bluegenes-tool-store* is used as a dependency in a different project, run the same command from that project's Clojure CLI setup. In a leiningen project you can instead add `"tools" ["run" "-m" "bluegenes-tool-store.tools"]` to the `:aliases` map in your *project.clj* and run `lein tools help` (this repository's *project.clj* already has the alias).

## Writing a tool: package layout and `config.json` reference

A tool is an npm package with the keyword `bluegenes-intermine-tool`, published under the `@intermine` scope. The tool store installs the latest version and, for each tool, reads three files from the package:

* `package.json`: the tool store keeps `name`, `version`, `description`, `license`, `homepage` and `author`.
* `config.json`: BlueGenes' own configuration of the tool (below).
* `dist/bundle.js`: the code BlueGenes loads (a warning is logged if it is missing). An optional `preview.png` in the package root is shown on the tools page.

`GET /api/tools/all` returns, for every installed tool, `{:names {:human :cljs :npm}, :config <the whole config.json>, :package {...}, :hasimage ...}`. The tool store does not filter `config.json`: every key you put in it, including keys of your own, reaches BlueGenes as `:config`. BlueGenes reads these keys:

| Key | Type | Meaning |
| --- | --- | --- |
| `toolName` | `{"human": "...", "cljs": "..."}` | The name shown to people and the global JavaScript variable the bundle defines (it must be a valid JS identifier and unique among tools). Required. |
| `version` | number | The BlueGenes tool API version the tool was written for (currently `2`). Tools with another version are not shown. Defaults to `1` when missing, so always set it. |
| `accepts` | list of `"id"` / `"ids"` | Whether the tool takes a single object (report page) or a list. |
| `classes` | list of class names | The data types the tool is shown for, e.g. `["Gene", "Protein"]`; `["*"]` means any. Subclasses match. |
| `depends` | list of class names | Classes the mine's data model must have for the tool to work, e.g. `["AtlasExpression"]`. BlueGenes hides the tool on mines whose model lacks any of them. |
| `widgets` | list of widget names | Widgets (names as listed by the mine's `/service/widgets`, e.g. `["go_enrichment_for_gene"]`) the tool requests from the mine's web service. BlueGenes hides the tool on mines that lack any of them. Declare these when the tool calls a widget or enrichment endpoint. |
| `threshold` | number | Largest list the tool is shown for before BlueGenes asks the user to expand it (default 1000). |
| `description` | string | Text shown on the tools page and above the tool; falls back to the package description. |
| `files` | `{"js": "dist/bundle.js", "css": "dist/style.css"}` | Where the bundle and its stylesheet are, relative to the package. |
| `columnMapping` | `{"Gene": {"id": "primaryIdentifier"}, ...}` | Which field identifies an object of each class. |

If your tool queries a mine for something that not every mine has (a class such as `Interaction` through `Gene.interactions`, or a widget), declare it in `depends` or `widgets` rather than letting the request fail at run time. Any other key (for example a `jbrowseUrl`) is passed to BlueGenes untouched and is available to the tool.

## Note on using OpenJDK 9

If you use OpenJDK 9, you will need to add the `java9` profile to your leiningen task. You can do this by adding `with-profile +java9` to your command, like the following:

    lein with-profile +java9 <YOUR_TASK>

## System requirements

* Java 8-11 (we recommend [OpenJDK](https://adoptopenjdk.net/))
* Latest [Leiningen](https://leiningen.org/)
