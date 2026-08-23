(ns sp-fuseki.render
  "Pure rendering: fuseki.edn -> assembler TTL, and auth -> shiro.ini.

  Pure on purpose. Everything here is a value-in/string-out function so the
  contract can be unit-tested without Docker (see test/render_test.clj), and so
  the boot path in entrypoint.clj stays orchestration you can read top to bottom.

  The RFC's line holds: this is a GENERATOR OVER THE ASSEMBLER TTL, never a
  replacement. A mounted config.ttl always wins untouched — see entrypoint.clj."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Reader tags — the part you can't get from hand-written TTL.
;; ---------------------------------------------------------------------------
;;
;; #env "VAR"   -> the environment variable, or a loud failure
;; #file "path" -> the file's trimmed contents (Docker/K8s secret, SOPS output)
;; #include "path" -> that file's EDN value, spliced in where the tag sits
;;
;; The first two are how a credential reaches the config without ever being IN
;; the config. The third is how a config with several datasets stops being one
;; unreadable file.
;;
;; This tag set is CLOSED and each member is documented in the README and
;; exercised by the tests. That is deliberate: taking a config library off the
;; shelf would bring its whole tag vocabulary with it, and every tag we didn't
;; document would be an extension point that works but isn't stated — the exact
;; property this image exists to not have. See RFC → Config.

(declare readers)

(def ^:private ^:dynamic *source*
  "The file currently being read, so #include resolves relative to the file that
  wrote it rather than the process's working directory. nil when parsing a string
  with no file behind it (tests, the built-in default)."
  nil)

(def ^:private ^:dynamic *include-stack*
  "Canonical paths of the files currently open, newest last. Cycle detection, and
  the trail printed when one is found."
  [])

(def ^:private max-include-depth
  "A cycle is caught exactly; this catches the pathological-but-acyclic case and,
  more usefully, stops a runaway from arriving as a StackOverflowError."
  10)

(defn- read-env [v]
  (when-not (string? v)
    (throw (ex-info (str "#env takes a string variable name, got: " (pr-str v)) {})))
  (or (System/getenv v)
      (throw (ex-info (str "#env \"" v "\" is not set in the environment") {:var v}))))

(defn- read-file-tag [v]
  (when-not (string? v)
    (throw (ex-info (str "#file takes a string path, got: " (pr-str v)) {})))
  (let [f (java.io.File. ^String v)]
    (when-not (.exists f)
      (throw (ex-info (str "#file \"" v "\" does not exist") {:path v})))
    (str/trim (slurp f))))

(defn- resolve-include
  "Where `#include \"x\"` actually points. Relative paths resolve against the
  including FILE's directory — not the working directory — so a config directory
  can be moved or mounted anywhere and still find its own parts."
  [^String v]
  (let [f (java.io.File. v)]
    (if (.isAbsolute f)
      f
      (java.io.File. ^java.io.File (or (some-> ^java.io.File *source* .getAbsoluteFile .getParentFile)
                                       (java.io.File. "."))
                     v))))

(defn- read-include [v]
  (when-not (string? v)
    (throw (ex-info (str "#include takes a string path, got: " (pr-str v)) {})))
  (let [f     (resolve-include v)
        canon (.getCanonicalPath f)]
    (when-not (.exists f)
      (throw (ex-info (str "#include \"" v "\" does not exist (resolved to " canon ")")
                      {:path canon})))
    ;; Checked before the depth limit so a genuine cycle reports as a cycle, with
    ;; the trail, rather than as "nested too deep" ten frames later.
    (when (some #{canon} *include-stack*)
      (throw (ex-info (str "#include cycle: "
                           (str/join " -> " (conj (vec *include-stack*) canon)))
                      {:cycle (conj (vec *include-stack*) canon)})))
    (when (>= (count *include-stack*) max-include-depth)
      (throw (ex-info (str "#include is nested more than " max-include-depth
                           " files deep — that is almost certainly a mistake")
                      {:depth (count *include-stack*)})))
    (binding [*source*        f
              *include-stack* (conj (vec *include-stack*) canon)]
      (edn/read-string {:readers readers} (slurp f)))))

;; Deliberately not confined to a directory. Whoever writes fuseki.edn already
;; controls the whole configuration and could mount a config.ttl instead, so
;; there is no privilege boundary here to defend — a sandbox would be theatre
;; that broke `#include "/etc/sp-fuseki/shared.edn"` for no gain.
(def readers {'env read-env 'file read-file-tag 'include read-include})

(defn parse
  "Parse fuseki.edn text, resolving #env/#file/#include. Throws with a readable
  message.

  `source` is the path the text came from, and it is what makes #include's
  relative resolution and cycle detection work. Omit it and #include still
  works, resolving against the working directory."
  ([text] (parse text nil))
  ([text source]
   (binding [*source*        (some-> ^String source (java.io.File.))
             *include-stack* (if source
                               [(.getCanonicalPath (java.io.File. ^String source))]
                               [])]
     (try
       (edn/read-string {:readers readers} text)
       (catch clojure.lang.ExceptionInfo e (throw e))
       (catch Exception e
         (throw (ex-info (str "fuseki.edn is not valid EDN: " (ex-message e)) {} e)))))))

;; ---------------------------------------------------------------------------
;; Validation — fail loudly at boot, never half-configured.
;; ---------------------------------------------------------------------------

(def ^:private top-level-keys #{:server :auth :prefixes :datasets :ui})
(def ^:private storages #{:mem :tdb2})
(def ^:private reasoners {:none nil
                          :rdfs      "http://jena.hpl.hp.com/2003/RDFSExptRuleReasoner"
                          :owl-micro "http://jena.hpl.hp.com/2003/OWLMicroFBRuleReasoner"})
;; operation -> [assembler predicate, Fuseki's default endpoint name].
;;
;; These are FUSEKI'S defaults, not ours, and that is the rule rather than an
;; accident of history. :query is "sparql" because that is what stock Fuseki
;; serves; a config written here and a config written by hand put the same
;; dataset at the same URL.
;;
;; Serving /ds/query as well was considered and rejected. It removes a real
;; papercut — writing `:endpoints #{:query}` and finding /ds/query is a 404 —
;; but it does so by shipping an alias Fuseki doesn't have, and this is a
;; notation FOR the assembler, not an improved Fuseki. The moment our default
;; behaviour is better than Fuseki's, the EDN has stopped being a spelling and
;; started being a product, and everything you learn here stops transferring.
;;
;; The papercut is answered instead by the two things below: :endpoints can name
;; every path explicitly, and the resolved routes are logged at boot, so the
;; surprise is stated rather than discovered by a 404.
(def ^:private operations {:query  ["fuseki:query"  "sparql"]
                           :update ["fuseki:update" "update"]
                           :gsp-rw ["fuseki:gsp-rw" "data"]
                           :gsp-r  ["fuseki:gsp-r"  "get"]})

;; Analyzers that need no parameters, so the keyword is the whole configuration.
;; Confirmed present in fuseki-server.jar rather than taken from documentation —
;; jena-text also ships Localized, Configurable and Generic analyzers, which all
;; take arguments and are therefore a schema of their own. Those are a TTL job
;; until someone needs them; refusing them by name beats half-supporting them.
(def ^:private analyzers {:standard           "text:StandardAnalyzer"
                          :keyword            "text:KeywordAnalyzer"
                          :simple             "text:SimpleAnalyzer"
                          :lower-case-keyword "text:LowerCaseKeywordAnalyzer"})

(def ^:private root-route
  "A nil name means the endpoint answers at the dataset root — /ds rather than
  /ds/sparql. In the assembler that is an endpoint with no fuseki:name, which is
  the same thing Fuseki's own legacy syntax spells as the empty string in
  `fuseki:serviceQuery \"sparql\", \"query\", \"\"`."
  nil)

(defn- route-names
  "One :endpoints map value -> the endpoint names it asks for.

  true          -> Fuseki's default name for that operation
  :foo or \"foo\" -> /ds/foo
  nil or \"\"     -> the dataset root
  a vector      -> several of the above, in the order written

  Keywords and strings both work and mean the same thing. Keywords read better
  and match how the rest of this format spells a known value (:tdb2, :rdfs,
  :anon); the string form stays because an endpoint name is a URL path segment
  and a keyword can't spell every legal one. In the TTL both become the same
  string literal.

  Returns ::bad for anything else so validate! can say which dataset and key."
  [op v]
  (let [one (fn [x]
              (cond (true? x)    (second (operations op))
                    (nil? x)     root-route
                    (keyword? x) (name x)
                    (string? x)  (when-not (str/blank? x) x)
                    :else        ::bad))]
    (if (vector? v) (mapv one v) [(one v)])))

(defn- endpoint-routes
  "Both spellings of :endpoints collapse here into an ordered seq of
  [operation name], name possibly nil for the root. Everything downstream —
  rendering, validation, the boot log — reads this one shape, so they cannot
  disagree about what a config asked for.

  Operations are sorted so the same EDN always renders byte-identical TTL; names
  within an operation keep the order they were written in, which a vector already
  makes deterministic."
  [endpoints]
  (if (map? endpoints)
    (for [op (sort-by (comp str name) (keys endpoints))
          nm (route-names op (get endpoints op))]
      [op nm])
    (for [op (sort-by (comp str name) endpoints)]
      [op (second (operations op))])))

(defn- bad [& msg] (throw (ex-info (apply str msg) {:validation true})))

(defn- show
  "A user value inside an error message, truncated.

  #include is deliberately unconfined, so a mistyped path can pull in a file the
  author never meant to read — and unlike the file itself, the LOG travels:
  shipped to a collector, pasted into an issue, printed in CI. `#include
  \"/etc/passwd\"` should tell you the shape was wrong without reproducing the
  contents. Naming what's wrong has never required quoting all of it."
  [v]
  (let [s (pr-str v)]
    (if (> (count s) 100)
      (str (subs s 0 100) "… (" (count s) " chars, truncated)")
      s)))

(defn- check-name
  "Dataset names become URL path segments, so reject anything that would make a
  broken endpoint rather than emitting TTL that half-works."
  [n]
  (when-not (string? n) (bad "dataset :name must be a string, got: " (show n)))
  (when (str/blank? n) (bad "dataset :name must not be blank"))
  (when (re-find #"[/\s?#]" n)
    (bad "dataset :name \"" n "\" must not contain '/', '?', '#' or whitespace"
         " — it becomes a URL path segment")))

(defn- check-endpoints
  "Both spellings are checked here, so the set form and the map form can't drift
  into having different rules."
  [d]
  (let [dsn       (:name d)
        endpoints (:endpoints d)
        known     (str/join ", " (sort (keys operations)))]
    (when-not (seq endpoints)
      (bad "dataset \"" dsn "\" needs at least one of :endpoints " known))
    (when-let [bad-ops (seq (remove operations (if (map? endpoints) (keys endpoints) endpoints)))]
      (bad "dataset \"" dsn "\" has unknown :endpoints "
           (str/join ", " (sort (map show bad-ops))) ". Known: " known))
    (when (map? endpoints)
      (doseq [[op v] endpoints]
        (when (some #{::bad} (route-names op v))
          (bad "dataset \"" dsn "\" :endpoints " op " must be true (Fuseki's"
               " default name), a keyword or string naming the path, nil for the"
               " dataset root, or a vector of those — got: " (show v)))))
    ;; Endpoint names are URL path segments, same as dataset names — reject one
    ;; that would make a broken route rather than emitting TTL that half-works.
    (doseq [[op nm] (endpoint-routes endpoints)
            :when (and nm (re-find #"[/\s?#]" nm))]
      (bad "dataset \"" dsn "\" :endpoints " op " name \"" nm "\" must not contain"
           " '/', '?', '#' or whitespace — it becomes a URL path segment"))
    (let [routes (endpoint-routes endpoints)]
      ;; The same operation asked for at the same path twice is redundant rather
      ;; than harmful, but it means the config says something it doesn't mean.
      (when-let [dupes (seq (for [[pair n] (frequencies routes) :when (> n 1)] pair))]
        (bad "dataset \"" dsn "\" asks for the same endpoint more than once: "
             (str/join ", " (for [[op nm] dupes]
                              (str op " at " (if nm (str "\"" nm "\"") "the dataset root"))))))
      ;; A name claimed by two operations IS harmful: one path, two meanings, and
      ;; which one answers is Fuseki's business rather than something we can state.
      ;; Two operations at the ROOT is fine and common — Fuseki dispatches those
      ;; on the request, which is what `fuseki:serviceQuery ""` relies on.
      (when-let [clashes (seq (for [[nm pairs] (group-by second routes)
                                    :when (and nm (> (count (distinct (map first pairs))) 1))]
                                nm))]
        (bad "dataset \"" dsn "\" gives the name "
             (str/join " and " (map #(str "\"" % "\"") (sort clashes)))
             " to more than one operation — one path can only mean one thing")))))

(defn- iri-ref
  "A predicate as the TTL should carry it: a namespaced keyword becomes a prefixed
  name, a string becomes a full <IRI>. Returns ::bad for anything else.

  Namespaced keywords are the point — :skos/prefLabel reuses the :prefixes you
  already declared, which is the ergonomic win over hand-written TTL where you
  repeat the prefix block in every file."
  [v]
  (cond
    (and (keyword? v) (namespace v)) (str (namespace v) ":" (name v))
    (string? v)                      (str "<" v ">")
    :else                            ::bad))

(defn- check-text
  "The text index is the block whose TTL is an RDF list of blank nodes, so it is
  where the notation pays hardest — and correspondingly where a half-validated
  config produces the least legible Jena error."
  [d prefixes]
  (let [dsn  (:name d)
        {:keys [directory analyzer store-values default-field fields] :as t} (:text d)]
    (when-not (map? t) (bad "dataset \"" dsn "\" :text must be a map"))
    (when-let [unknown (seq (remove #{:directory :analyzer :store-values :default-field :fields} (keys t)))]
      (bad "dataset \"" dsn "\" :text has unknown key(s) " (str/join ", " (sort unknown))
           ". Known: :directory, :analyzer, :store-values, :default-field, :fields"))
    (when-not (and (map? fields) (seq fields))
      (bad "dataset \"" dsn "\" :text needs :fields — a map of field name to"
           " predicate, e.g. {:label :rdfs/label}. An index over nothing would"
           " build cleanly and then never match"))
    (doseq [[f p] fields]
      (when-not (keyword? f)
        (bad "dataset \"" dsn "\" :text :fields keys must be keywords (the field"
             " name you query by), got: " (show f)))
      (when (= ::bad (iri-ref p))
        (bad "dataset \"" dsn "\" :text :fields " f " must be a namespaced keyword"
             " like :rdfs/label, or a full IRI string — got: " (show p))))
    ;; A prefix used but not declared renders TTL that Jena refuses to parse, and
    ;; its message is about the TTL, not about the EDN anyone wrote.
    (doseq [[f p] fields
            :when (and (keyword? p) (namespace p))
            :when (not (contains? prefixes (keyword (namespace p))))]
      (bad "dataset \"" dsn "\" :text :fields " f " uses prefix \"" (namespace p)
           "\" which is not in :prefixes — declare it there, or write the full IRI"
           " as a string"))
    (when default-field
      (when-not (contains? fields default-field)
        (bad "dataset \"" dsn "\" :text :default-field " (show default-field)
             " is not one of :fields (" (str/join ", " (sort (map str (keys fields)))) ")")))
    (when (and analyzer (not (contains? analyzers analyzer)))
      (bad "dataset \"" dsn "\" :text :analyzer must be one of "
           (str/join ", " (sort (keys analyzers))) ", got: " (show analyzer)
           " — jena-text's parameterised analyzers (localized, configurable,"
           " generic) are a config of their own; mount a config.ttl for those"))
    (when (and (some? store-values) (not (boolean? store-values)))
      (bad "dataset \"" dsn "\" :text :store-values must be true or false, got: " (show store-values)))
    (when (and directory (not (string? directory)))
      (bad "dataset \"" dsn "\" :text :directory must be a path string, or \"mem\""
           " for an in-memory index, got: " (show directory)))))

(defn validate!
  "Throw with an actionable message, or return the config unchanged."
  [cfg]
  (when-not (map? cfg) (bad "fuseki.edn must be a map, got: " (show cfg)))
  (when-let [unknown (seq (remove top-level-keys (keys cfg)))]
    (bad "unknown top-level key(s) " (str/join ", " (sort unknown))
         ". Known: " (str/join ", " (sort top-level-keys))
         ;; :federation is in the sketch but unimplemented. Say so instead of
         ;; silently dropping it — a dropped key is a config that lies.
         (when (some #{:federation} unknown)
           " — :federation is in the design sketch (issue #2) but NOT implemented; remove it or use a mounted config.ttl")))
  (let [{:keys [server auth prefixes datasets ui]} cfg]
    (when (and server (not (map? server))) (bad ":server must be a map"))
    (when-let [p (:port server)]
      (when-not (and (integer? p) (< 0 p 65536)) (bad ":server :port must be an integer 1-65535, got: " (show p))))
    (when auth
      (when-not (map? auth) (bad ":auth must be a map, e.g. {:mode :anon}"))
      ;; Unknown keys were silently accepted, which is how :password came to be
      ;; documented in examples/fuseki.edn and ignored by the entrypoint.
      (when-let [unknown (seq (remove #{:mode :user :password} (keys auth)))]
        (bad ":auth has unknown key(s) " (str/join ", " (sort unknown))
             ". Known: :mode, :user, :password"))
      (when-not (#{:anon :basic} (:mode auth))
        (bad ":auth :mode must be :anon or :basic, got: " (show (:mode auth))))
      (doseq [k [:user :password]]
        (when-let [v (get auth k)]
          (when-not (string? v)
            (bad ":auth " k " must be a string — use #env or #file to read it at boot,"
                 " so the secret never lives in this file. Got: " (show v)))))
      (when (and (:password auth) (= :anon (:mode auth)))
        (bad ":auth :password is set but :mode is :anon — no credentials are used in"
             " anon mode, so this would silently do nothing")))
    (when prefixes
      (when-not (map? prefixes) (bad ":prefixes must be a map of keyword -> IRI string"))
      (doseq [[k v] prefixes]
        (when-not (keyword? k) (bad ":prefixes keys must be keywords, got: " (show k)))
        (when-not (string? v) (bad ":prefixes values must be IRI strings, got: " (show v)))))
    (when ui
      (when-not (map? ui) (bad ":ui must be a map, e.g. {:enabled true}"))
      (when-not (boolean? (:enabled ui)) (bad ":ui :enabled must be true or false")))
    (when-not (seq datasets) (bad ":datasets must be a non-empty vector"))
    ;; Two datasets with one name is the endpoint-ambiguity rule one level up:
    ;; one path can only mean one thing. Jena does catch it —
    ;; "FusekiConfigException: Data service name already registered: /x" — but by
    ;; then we have already printed routes for both, so the boot log advertises
    ;; datasets that never serve, and Jena's message names neither the file nor
    ;; which of the two to change. :name is our key, so this is ours to refuse.
    ;;
    ;; #include is what makes it reachable rather than theoretical: including the
    ;; same file twice is a plausible copy-paste, and two files can collide with
    ;; neither of them looking wrong on its own.
    (when-let [dupes (seq (for [[n c] (frequencies (keep :name datasets)) :when (> c 1)] n))]
      (bad "two or more datasets share the name "
           (str/join " and " (map #(str "\"" % "\"") (sort dupes)))
           " — each becomes a URL path, and one path can only mean one thing"
           " (if these came from #include, the same file may be included twice)"))
    (doseq [d datasets]
      (when-not (map? d) (bad "each dataset must be a map, got: " (show d)))
      (check-name (:name d))
      (when-not (storages (:storage d))
        (bad "dataset \"" (:name d) "\" :storage must be one of "
             (str/join ", " (sort storages)) ", got: " (show (:storage d))))
      (check-endpoints d)
      (when (:text d) (check-text d (:prefixes cfg)))
      (when (and (:text d) (:reasoner d) (not= :none (:reasoner d)))
        (bad "dataset \"" (:name d) "\": :text with :reasoner is not supported"
             " — whether the index should see entailed triples is a decision we"
             " haven't made, and guessing it would be a config that lies"
             " (mount a config.ttl if you need both)"))
      (when-let [r (:reasoner d)]
        (when-not (contains? reasoners r)
          (bad "dataset \"" (:name d) "\" :reasoner must be one of "
               (str/join ", " (sort (keys reasoners))) ", got: " (show r))))
      (when (and (:reasoner d) (not= :none (:reasoner d)) (= :tdb2 (:storage d)))
        (bad "dataset \"" (:name d) "\": :reasoner on :tdb2 storage is not supported"
             " — inference over a persistent store needs a decision we haven't made"
             " (put the reasoner on a :mem dataset, or mount a config.ttl)"))))
  cfg)

;; ---------------------------------------------------------------------------
;; TTL rendering
;; ---------------------------------------------------------------------------

(def ^:private base-prefixes
  [["fuseki" "http://jena.apache.org/fuseki#"]
   ["ja"     "http://jena.hpl.hp.com/2005/11/Assembler#"]
   ["tdb2"   "http://jena.apache.org/2016/tdb#"]])

(defn- prefix-lines
  "Assembler prefixes always, so the output stands alone; text: only when a
  dataset actually uses it, because an unused prefix in a file people are told to
  go and read is noise."
  [prefixes uses-text?]
  (let [base (cond-> base-prefixes
               uses-text? (conj ["text" "http://jena.apache.org/text#"]))
        user (map (fn [[k v]] [(name k) v]) (sort-by key prefixes))]
    (for [[p iri] (concat base user)]
      (format "@prefix %-7s <%s> ." (str p ":") iri))))

(defn- endpoint-lines [endpoints]
  (for [[op nm] (endpoint-routes endpoints)
        :let [[operation] (operations op)]]
    ;; Operation padded so the effective config lines up when you read it — it's
    ;; a file humans are told to go and look at. An endpoint with no fuseki:name
    ;; is how the assembler spells "answers at the dataset root".
    (if nm
      (format "   fuseki:endpoint [ fuseki:operation %-14s ; fuseki:name \"%s\" ] ;" operation nm)
      (format "   fuseki:endpoint [ fuseki:operation %-14s ] ;" operation))))

(defn- graph-block
  "The default graph for a :mem dataset, wrapped in an InfModel if a reasoner is on."
  [reasoner]
  (if-let [url (get reasoners reasoner)]
    (str "[ a ja:InfModel ;\n"
         "                        ja:baseModel [ a ja:MemoryModel ] ;\n"
         "                        ja:reasoner  [ ja:reasonerURL <" url "> ] ]")
    "[ a ja:MemoryModel ]"))

(defn- storage-block
  "The dataset node itself, indented to sit at `indent` columns. Split out from
  dataset-block because a text index WRAPS this rather than replacing it, and the
  wrapper needs the same node one level further in."
  [{:keys [name storage reasoner]} tdb2-root indent]
  (let [pad (apply str (repeat indent \space))]
    (case storage
      :mem (str "[ a ja:RDFDataset ;\n"
                pad "    ja:defaultGraph " (graph-block reasoner) " ]")
      ;; Absolute location under a mount the container can write as uid 1000.
      ;; Getting this wrong is the "IOException: No such file or directory" trap.
      :tdb2 (str "[ a tdb2:DatasetTDB2 ;\n"
                 pad "    tdb2:location \"" tdb2-root "/" name "\" ]")))) 

 (defn- text-statements
  "The text index and entity map, as NAMED top-level resources.

  Not inline blank nodes, and this is not cosmetic: jena-text's
  EntityDefinitionAssembler reads the entity map by building a query with
  ParameterizedSparqlString.setIri, so a blank node arrives as a null IRI and the
  whole config dies with a NullPointerException naming nothing you wrote. Every
  hand-written example names these for the same reason. <#frag> is Fuseki's own
  idiom, resolved against the config file, so no invented prefix is needed.

  The text:map is an RDF LIST of blank nodes — the single most miserable thing to
  write by hand here, and why :text earns a place in the notation at all."
  [d tdb2-root]
  (when-let [{:keys [directory analyzer store-values default-field fields]} (:text d)]
    (let [n      (:name d)
          dir    (or directory (str tdb2-root "/" n "-lucene"))
          ;; "mem" is jena-text's in-memory index, a bare literal rather than a
          ;; file: IRI. Anything else is a directory on disk.
          dir-tt (if (= dir "mem") "\"mem\"" (str "<file:" dir ">"))
          deflt  (or default-field (first (sort-by str (keys fields))))
          entries (for [[f p] (sort-by (comp str key) fields)]
                    (str "     [ text:field \"" (name f) "\" ; text:predicate " (iri-ref p) " ]"))]
      (str "<#" n "-textindex> a text:TextIndexLucene ;\n"
           "   text:directory " dir-tt " ;\n"
           (when store-values "   text:storeValues true ;\n")
           "   text:analyzer  [ a " (get analyzers (or analyzer :standard)) " ] ;\n"
           "   text:entityMap <#" n "-entitymap> .\n"
           "\n"
           "<#" n "-entitymap> a text:EntityMap ;\n"
           "   text:defaultField \"" (name deflt) "\" ;\n"
           ;; Required by jena-text and never varied in practice: an EntityMap
           ;; with no text:entityField makes Jena refuse the config outright, and
           ;; the value is only observable to something reading the Lucene index
           ;; directly. A constant, not a key.
           "   text:entityField  \"uri\" ;\n"
           "   text:map (\n"
           (str/join "\n" entries) "\n"
           "   ) .")))) 

 (defn- dataset-block
  "The fuseki:dataset line. With :text the storage node is WRAPPED in a
  text:TextDataset rather than replaced — the index sits alongside the real
  store, which is why :text is a shape change to a dataset and not another key
  on one."
  [d tdb2-root]
  (if (:text d)
    (str "   fuseki:dataset [ a text:TextDataset ;\n"
         "                    text:dataset " (storage-block d tdb2-root 20) " ;\n"
         "                    text:index   <#" (:name d) "-textindex> ] .")
    (str "   fuseki:dataset " (storage-block d tdb2-root 17) " .")))

(defn- service-block [d tdb2-root]
  (str/join "\n"
            (concat ["[] a fuseki:Service ;"
                     (format "   fuseki:name \"%s\" ;" (:name d))]
                    (endpoint-lines (:endpoints d))
                    [(dataset-block d tdb2-root)])))

(defn edn->ttl
  "Render validated config to assembler TTL.

  opts: :source    path the EDN came from (for the header)
        :tdb2-root directory TDB2 locations go under (default /fuseki/databases)"
  [cfg & [{:keys [source tdb2-root] :or {source "fuseki.edn"
                                         tdb2-root "/fuseki/databases"}}]]
  (validate! cfg)
  (str/join
   "\n"
   (concat
    [(str "# sp-fuseki: GENERATED from " source " — do not edit; edit the EDN.")
     "# This is the *effective* config Fuseki was handed. Mount your own"
     "# config.ttl instead and it is honoured untouched (and this file is not used)."
     ""]
    (prefix-lines (:prefixes cfg) (boolean (some :text (:datasets cfg))))
    [""]
    (interpose "" (map #(service-block % tdb2-root) (:datasets cfg)))
    [""]
    ;; Named, so they come after the services rather than nesting inside them —
    ;; see text-statements for why they cannot be inline blank nodes.
    (interpose "" (keep #(text-statements % tdb2-root) (:datasets cfg)))
    [""])))

(defn routes
  "What a config will answer on, as [dataset-name [[operation [path ...]] ...]].

  A route is a resolved decision exactly like :auth or :port, and an unlogged one
  is how `:endpoints #{:query}` serving /ds/sparql could be a silent 404 for
  someone who reasonably expected /ds/query. Logged at boot for the same reason
  those are — see entrypoint.clj.

  Grouped BY OPERATION rather than listed flat, because a flat list of paths
  half-answers the only question the line exists for. `routes: x -> /x` is true
  and useless when /x serves query, update and gsp-rw: someone debugging
  \"why does POST /x fail\" cannot learn from it whether update is at the root.
  Grouping also fixes the ordering, which was otherwise an artefact of the sort.

  Operations arrive already sorted from endpoint-routes, so partition-by keeps
  that order rather than reintroducing hash-map arbitrariness."
  [cfg]
  (for [d (:datasets cfg)]
    [(:name d)
     (for [pairs (partition-by first (endpoint-routes (:endpoints d)))]
       [(ffirst pairs)
        (distinct (for [[_ nm] pairs]
                    (if nm (str "/" (:name d) "/" nm) (str "/" (:name d)))))])]))

;; ---------------------------------------------------------------------------
;; shiro.ini rendering — shared by the env-driven and EDN-driven paths, so the
;; admin fencing can't drift between them.
;; ---------------------------------------------------------------------------

(def shiro-anon
  "# sp-fuseki: GENERATED — anonymous (throwaway/lab). NOT for exposed use.
#
# Data endpoints are open; the MUTATING admin API is not. `POST /$/datasets`
# creates datasets and `DELETE /$/datasets/{name}` drops them, so leaving all of
# /$/ anonymous means anyone who can reach the port can delete your data. Shiro
# matches on path, not method, so fencing the mutation means fencing the path:
# with no [users] defined, authcBasic is a permanent 401 and admin is simply
# closed. Set auth mode :basic (FUSEKI_AUTH=basic) to actually use the admin API.
#
# Read-only admin stays open so the UI (and your monitoring) still reports.
#
# The realm below is the 401's only chance to explain itself. A bare
# WWW-Authenticate BASIC realm of `application` against an empty [users] is a dead
# end — there are no credentials that work, and nothing says so. Shiro echoes
# applicationName into that header, so the reason and the fix arrive with the
# refusal instead of waiting in this file for someone who thought to look.
#
# (This is a Clojure string literal: no double quotes below, or the def ends here.)
[main]
ssl.enabled = false
authcBasic.applicationName = sp-fuseki admin is CLOSED in anon mode - no credentials exist. Set FUSEKI_AUTH=basic to open it.
[users]
[roles]
[urls]
/$/ping = anon
/$/server = anon
/$/stats = anon
/$/stats/** = anon
/$/metrics = anon
/$/** = authcBasic
/** = anon
")

(defn shiro-basic [user pw]
  (when (str/blank? (str user)) (bad "basic auth needs a username"))
  (when (str/blank? (str pw)) (bad "basic auth needs a password"))
  (format "# sp-fuseki: GENERATED — basic auth, all endpoints require login.
#
# /$/ping is the one exception, and it has to be: the image's HEALTHCHECK curls
# it with no credentials, so gating it makes every basic-auth container report
# unhealthy forever. Ping returns a timestamp and nothing else.
[main]
ssl.enabled = false
authcBasic.applicationName = sp-fuseki
[users]
%s = %s
[roles]
[urls]
/$/ping = anon
/** = authcBasic
" user pw))
