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
;; operation -> [assembler predicate, Fuseki's conventional endpoint name].
;;
;; The conventional names are Fuseki's, not ours — :query is "sparql" because
;; that is what stock Fuseki serves a query endpoint at. It surprises people
;; reasonably often (`:query` in the EDN, /ds/query a 404) which is why the
;; resolved routes are logged at boot and why :endpoints can name them
;; explicitly. Changing the DEFAULT would silently move the URLs of anyone
;; already running the published image, so it stays.
(def ^:private operations {:query  ["fuseki:query"  "sparql"]
                           :update ["fuseki:update" "update"]
                           :gsp-rw ["fuseki:gsp-rw" "data"]
                           :gsp-r  ["fuseki:gsp-r"  "get"]})

(def ^:private root-route
  "A nil name means the endpoint answers at the dataset root — /ds rather than
  /ds/sparql. In the assembler that is an endpoint with no fuseki:name, which is
  the same thing Fuseki's own legacy syntax spells as the empty string in
  `fuseki:serviceQuery \"sparql\", \"query\", \"\"`."
  nil)

(defn- route-names
  "One :endpoints map value -> the endpoint names it asks for.

  true       -> Fuseki's conventional name for that operation
  \"foo\"      -> /ds/foo
  nil or \"\"  -> the dataset root
  a vector   -> several of the above, in the order written

  Returns ::bad for anything else so validate! can say which dataset and key."
  [op v]
  (let [one (fn [x]
              (cond (true? x)   (second (operations op))
                    (nil? x)    root-route
                    (string? x) (when-not (str/blank? x) x)
                    :else       ::bad))]
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

(defn- check-name
  "Dataset names become URL path segments, so reject anything that would make a
  broken endpoint rather than emitting TTL that half-works."
  [n]
  (when-not (string? n) (bad "dataset :name must be a string, got: " (pr-str n)))
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
           (str/join ", " (sort (map pr-str bad-ops))) ". Known: " known))
    (when (map? endpoints)
      (doseq [[op v] endpoints]
        (when (some #{::bad} (route-names op v))
          (bad "dataset \"" dsn "\" :endpoints " op " must be true (Fuseki's"
               " conventional name), a string, nil for the dataset root, or a"
               " vector of those — got: " (pr-str v)))))
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

(defn validate!
  "Throw with an actionable message, or return the config unchanged."
  [cfg]
  (when-not (map? cfg) (bad "fuseki.edn must be a map, got: " (pr-str cfg)))
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
      (when-not (and (integer? p) (< 0 p 65536)) (bad ":server :port must be an integer 1-65535, got: " (pr-str p))))
    (when auth
      (when-not (map? auth) (bad ":auth must be a map, e.g. {:mode :anon}"))
      ;; Unknown keys were silently accepted, which is how :password came to be
      ;; documented in examples/fuseki.edn and ignored by the entrypoint.
      (when-let [unknown (seq (remove #{:mode :user :password} (keys auth)))]
        (bad ":auth has unknown key(s) " (str/join ", " (sort unknown))
             ". Known: :mode, :user, :password"))
      (when-not (#{:anon :basic} (:mode auth))
        (bad ":auth :mode must be :anon or :basic, got: " (pr-str (:mode auth))))
      (doseq [k [:user :password]]
        (when-let [v (get auth k)]
          (when-not (string? v)
            (bad ":auth " k " must be a string — use #env or #file to read it at boot,"
                 " so the secret never lives in this file. Got: " (pr-str v)))))
      (when (and (:password auth) (= :anon (:mode auth)))
        (bad ":auth :password is set but :mode is :anon — no credentials are used in"
             " anon mode, so this would silently do nothing")))
    (when prefixes
      (when-not (map? prefixes) (bad ":prefixes must be a map of keyword -> IRI string"))
      (doseq [[k v] prefixes]
        (when-not (keyword? k) (bad ":prefixes keys must be keywords, got: " (pr-str k)))
        (when-not (string? v) (bad ":prefixes values must be IRI strings, got: " (pr-str v)))))
    (when ui
      (when-not (map? ui) (bad ":ui must be a map, e.g. {:enabled true}"))
      (when-not (boolean? (:enabled ui)) (bad ":ui :enabled must be true or false")))
    (when-not (seq datasets) (bad ":datasets must be a non-empty vector"))
    (doseq [d datasets]
      (when-not (map? d) (bad "each dataset must be a map, got: " (pr-str d)))
      (check-name (:name d))
      (when-not (storages (:storage d))
        (bad "dataset \"" (:name d) "\" :storage must be one of "
             (str/join ", " (sort storages)) ", got: " (pr-str (:storage d))))
      (check-endpoints d)
      (when-let [r (:reasoner d)]
        (when-not (contains? reasoners r)
          (bad "dataset \"" (:name d) "\" :reasoner must be one of "
               (str/join ", " (sort (keys reasoners))) ", got: " (pr-str r))))
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

(defn- prefix-lines [prefixes]
  (let [user (map (fn [[k v]] [(name k) v]) (sort-by key prefixes))]
    (for [[p iri] (concat base-prefixes user)]
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

(defn- dataset-block [{:keys [name storage reasoner]} tdb2-root]
  (case storage
    :mem (str "   fuseki:dataset [ a ja:RDFDataset ;\n"
              "                    ja:defaultGraph " (graph-block reasoner) " ] .")
    ;; Absolute location under a mount the container can write as uid 1000.
    ;; Getting this wrong is the "IOException: No such file or directory" trap.
    :tdb2 (str "   fuseki:dataset [ a tdb2:DatasetTDB2 ;\n"
               "                    tdb2:location \"" tdb2-root "/" name "\" ] .")))

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
    (prefix-lines (:prefixes cfg))
    [""]
    (interpose "" (map #(service-block % tdb2-root) (:datasets cfg)))
    [""])))

(defn routes
  "The URL paths a config will answer on, as [dataset-name [path ...]] pairs.

  A route is a resolved decision exactly like :auth or :port, and an unlogged one
  is how `:endpoints #{:query}` serving /ds/sparql could be a silent 404 for
  someone who reasonably expected /ds/query. Logged at boot for the same reason
  those are — see entrypoint.clj."
  [cfg]
  (for [d (:datasets cfg)]
    [(:name d)
     (distinct (for [[_ nm] (endpoint-routes (:endpoints d))]
                 (if nm (str "/" (:name d) "/" nm) (str "/" (:name d)))))]))

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
[main]
ssl.enabled = false
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
[users]
%s = %s
[roles]
[urls]
/$/ping = anon
/** = authcBasic
" user pw))
