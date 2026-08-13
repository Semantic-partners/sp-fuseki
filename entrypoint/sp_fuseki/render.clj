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
;; #env "VAR"  -> the environment variable, or a loud failure
;; #file "path" -> the file's trimmed contents (Docker/K8s secret, SOPS output)
;;
;; This is how a credential reaches the config without ever being IN the config.

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

(def readers {'env read-env 'file read-file-tag})

(defn parse
  "Parse fuseki.edn text, resolving #env/#file. Throws with a readable message."
  [text]
  (try
    (edn/read-string {:readers readers} text)
    (catch clojure.lang.ExceptionInfo e (throw e))
    (catch Exception e
      (throw (ex-info (str "fuseki.edn is not valid EDN: " (ex-message e)) {} e)))))

;; ---------------------------------------------------------------------------
;; Validation — fail loudly at boot, never half-configured.
;; ---------------------------------------------------------------------------

(def ^:private top-level-keys #{:server :auth :prefixes :datasets :ui})
(def ^:private storages #{:mem :tdb2})
(def ^:private reasoners {:none nil
                          :rdfs      "http://jena.hpl.hp.com/2003/RDFSExptRuleReasoner"
                          :owl-micro "http://jena.hpl.hp.com/2003/OWLMicroFBRuleReasoner"})
(def ^:private operations {:query  ["fuseki:query"  "sparql"]
                           :update ["fuseki:update" "update"]
                           :gsp-rw ["fuseki:gsp-rw" "data"]
                           :gsp-r  ["fuseki:gsp-r"  "get"]})

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
      (when-not (seq (:endpoints d))
        (bad "dataset \"" (:name d) "\" needs at least one of :endpoints "
             (str/join ", " (sort (keys operations)))))
      (when-let [bad-ops (seq (remove operations (:endpoints d)))]
        (bad "dataset \"" (:name d) "\" has unknown :endpoints "
             (str/join ", " (sort bad-ops)) ". Known: " (str/join ", " (sort (keys operations)))))
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
  ;; Sorted so the same EDN always renders byte-identical TTL — a diffable
  ;; effective config is worth more than preserving set order (which sets lack).
  (for [op (sort-by (comp str name) endpoints)
        :let [[operation ep-name] (operations op)]]
    ;; Operation padded so the effective config lines up when you read it — it's
    ;; a file humans are told to go and look at.
    (format "   fuseki:endpoint [ fuseki:operation %-14s ; fuseki:name \"%s\" ] ;" operation ep-name)))

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
