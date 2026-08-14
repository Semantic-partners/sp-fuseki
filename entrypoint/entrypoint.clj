#!/usr/bin/env bb
;; sp-fuseki entrypoint — legible boot orchestration.
;;
;; This is the contract. Every knob below is documented in the README; nothing
;; here is magic you have to reverse-engineer. The job: resolve config + shiro,
;; write the *effective* files where you can read them, then exec Fuseki.
;;
;; Rendering lives in sp_fuseki/render.clj (pure, unit-tested). There is exactly
;; ONE TTL generator: the zero-config default is an EDN value rendered by the
;; same code path as a user's fuseki.edn, so the two cannot drift apart.
;;
;; Environment (the extension points):
;;   FUSEKI_BASE                  runtime/data dir              (default /fuseki/run)
;;   FUSEKI_CONFIG                assembler config.ttl          (default /fuseki/config.ttl)
;;   FUSEKI_EDN                   fuseki.edn (rendered to TTL)  (default /fuseki/fuseki.edn)
;;   FUSEKI_SHIRO                 shiro.ini                     (default /fuseki/shiro.ini)
;;   FUSEKI_PORT                  listen port                   (default 3030)
;;                                — also settable as :server {:port n} in fuseki.edn
;;   FUSEKI_DATASET               default ds name (no config)   (default ds)
;;   FUSEKI_AUTH                  anon | basic                  (default anon)
;;                                — also settable as :auth {:mode ...} in fuseki.edn
;;   FUSEKI_ADMIN_USER            basic-auth user               (default admin)
;;   FUSEKI_ADMIN_PASSWORD        basic-auth secret (env)
;;   FUSEKI_ADMIN_PASSWORD_FILE   basic-auth secret (file; e.g. a Docker secret)
;;                                — or :auth {:user .. :password ..} in fuseki.edn,
;;                                  with #env/#file so the secret isn't in the file
;;   FUSEKI_UI                    on | off                      (default on)
;;                                — also settable as :ui {:enabled ...} in fuseki.edn
;;
;; Precedence for the two settings the EDN can also carry (:auth, :ui): an
;; explicitly set env var wins, then the EDN, then the default — and the resolved
;; value is logged WITH ITS SOURCE, so "why is the UI off" never needs a bisect.
;; The EDN's copies apply only when the EDN is the config source; a mounted
;; config.ttl means the EDN was ignored wholesale.
;;   FUSEKI_TDB2_ROOT             where :tdb2 datasets live      (default /fuseki/databases)
;;   FUSEKI_JAR                   fuseki-server.jar             (default /opt/fuseki/fuseki-server.jar)
;;
;; Config resolution (config-respecting, never silently regenerated):
;;   1. FUSEKI_CONFIG mounted -> honoured untouched. If a fuseki.edn is ALSO
;;      mounted, the TTL wins and we log that the EDN was ignored — conflicting
;;      sources of truth must never be a silent surprise.
;;   2. FUSEKI_EDN mounted    -> validated and rendered to TTL.
;;   3. neither               -> a minimal in-memory dataset, via the same renderer.
;;   The effective config + shiro are always written under FUSEKI_BASE and the
;;   paths logged, so "what did it actually run" is never a mystery.

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[sp-fuseki.render :as render])

(defn env [k d] (or (System/getenv k) d))
(defn log [& xs] (binding [*out* *err*] (apply println "[sp-fuseki]" xs)))
(defn die [& xs]
  (binding [*out* *err*] (apply println "[sp-fuseki] FATAL:" xs))
  (System/exit 1))

(def base      (env "FUSEKI_BASE"       "/fuseki/run"))
;; Held raw as well as resolved, for the same reason :auth/:ui/:port are: absence
;; of the DEFAULT path means "no config of that kind, carry on", while absence of
;; a path someone EXPLICITLY set is an instruction we couldn't honour. Silently
;; falling through to the generated default there hands you a working server
;; serving something you didn't ask for — which is the whole bug family this
;; image exists to close. FUSEKI_ADMIN_PASSWORD_FILE already got this right.
(def env-cfg   (System/getenv "FUSEKI_CONFIG"))
(def env-edn-p (System/getenv "FUSEKI_EDN"))
(def env-shiro (System/getenv "FUSEKI_SHIRO"))
(def cfg-in    (or env-cfg   "/fuseki/config.ttl"))
(def edn-in    (or env-edn-p "/fuseki/fuseki.edn"))
(def shiro-in  (or env-shiro "/fuseki/shiro.ini"))
;; Raw, so "explicitly set" stays distinguishable from "defaulted" — that is what
;; the precedence rule needs. Resolved in -main, because the EDN may supply it.
(def env-port     (System/getenv "FUSEKI_PORT"))
(def port-default "3030")
(def ds-name   (env "FUSEKI_DATASET"    "ds"))
(def tdb2-root (env "FUSEKI_TDB2_ROOT"  "/fuseki/databases"))

;; Settings the EDN can also carry. Held as raw env so "explicitly set" is
;; distinguishable from "defaulted" — that distinction IS the precedence rule:
;; an explicit env var beats the file, the file beats the default.
(def env-auth (System/getenv "FUSEKI_AUTH"))
(def env-ui   (System/getenv "FUSEKI_UI"))
(def auth-default "anon")
(def ui-default   "on")
(def jar       (env "FUSEKI_JAR"        "/opt/fuseki/fuseki-server.jar"))

;; The one jar carries both servers. Its manifest Main-Class is the UI + admin
;; build (what `java -jar` gets you); this is the headless one, same class the
;; dist's own `fuseki-plain` script selects. So FUSEKI_UI is a runtime choice —
;; no second image, no extra build leg.
(def plain-main "org.apache.jena.fuseki.main.cmds.FusekiServerPlainCmd")

(defn default-edn
  "The zero-config default, as data. Rendered by the same code as a user's EDN."
  []
  {:datasets [{:name ds-name
               :storage :mem
               :endpoints #{:query :update :gsp-rw}}]})

(defn read-secret
  "The basic-auth password, and where it came from. Env wins over the EDN, same
  precedence as every other shared setting.

  `edn-pw` has already had #env/#file resolved by the reader, so by the time it
  arrives it is a plain string — which is the point of those tags: the secret is
  read at boot and never written in the config."
  [edn-pw]
  (let [pw  (System/getenv "FUSEKI_ADMIN_PASSWORD")
        pwf (System/getenv "FUSEKI_ADMIN_PASSWORD_FILE")]
    (cond
      (and pwf (fs/exists? pwf)) [(str/trim (slurp pwf)) (str "FUSEKI_ADMIN_PASSWORD_FILE " pwf)]
      (and pwf (not (fs/exists? pwf))) (die "FUSEKI_ADMIN_PASSWORD_FILE set but not found:" pwf)
      pw          [pw "FUSEKI_ADMIN_PASSWORD"]
      edn-pw      [edn-pw "fuseki.edn :auth :password"]
      :else       [nil nil])))

;; ---------------------------------------------------------------------------
;; Inputs we were handed and did not use
;; ---------------------------------------------------------------------------
;;
;; The contract is that a resolved value is logged WITH ITS SOURCE. An input that
;; resolves to nothing at all was getting no line, which is the same "config that
;; lies" this image refuses in the file — just sourced from the environment.
;;
;; Two families, both real: FUSEKI_* we don't recognise (a typo, or a variable
;; from another image), and the stain/jena-fuseki names a migrator will reach for
;; out of habit. FUSEKI_DATASET_N is deliberately NOT implemented — a magic env
;; var conjuring a dataset whose storage and endpoints are written down nowhere is
;; the pattern this project exists to reject, and it would be a third way to
;; declare a dataset beside config.ttl and fuseki.edn. Saying so is the fix.

(def ^:private consumed
  #{"FUSEKI_BASE" "FUSEKI_CONFIG" "FUSEKI_EDN" "FUSEKI_SHIRO" "FUSEKI_PORT"
    "FUSEKI_DATASET" "FUSEKI_AUTH" "FUSEKI_UI" "FUSEKI_JAR" "FUSEKI_TDB2_ROOT"
    "FUSEKI_ADMIN_USER" "FUSEKI_ADMIN_PASSWORD" "FUSEKI_ADMIN_PASSWORD_FILE"})

(def ^:private inherited-from-elsewhere
  "Names Fuseki's own scripts and the base image set, which are not ours to
  explain. Reporting these would train people to ignore the whole line."
  #{"FUSEKI_HOME" "JAVA_HOME" "JAVA_OPTS" "JVM_ARGS"})

(def ^:private stain-vars
  "stain/jena-fuseki's interface, with what to use instead. Named individually
  because 'unrecognised' is not actionable and 'use :datasets' is."
  {"ADMIN_PASSWORD"  "FUSEKI_ADMIN_PASSWORD (or :auth {:password #env \"...\"} in fuseki.edn)"
   "FUSEKI_DATASET_1" "a :datasets entry in fuseki.edn"
   "ENABLE_DATA_WRITE" ":endpoints #{:gsp-rw} on the dataset"
   "ENABLE_UPDATE"     ":endpoints #{:update} on the dataset"
   "ENABLE_UPLOAD"     ":endpoints #{:gsp-rw} on the dataset"
   "QUERY_TIMEOUT"     "a mounted config.ttl — no EDN key for ARQ timeouts yet"})

(defn report-unused-env!
  "Log inputs we were given and did not act on. A warning, never fatal: the
  environment is not ours alone, and refusing to boot over a stray variable would
  be worse than the silence it replaces."
  []
  (let [names (set (keys (System/getenv)))]
    (doseq [[v instead] (sort stain-vars)
            :when (contains? names v)]
      (log "WARNING:" v "is not read by this image — it is stain/jena-fuseki's."
           "Use" (str instead ".")))
    ;; FUSEKI_DATASET_2 and up, which the map above cannot enumerate.
    (doseq [v (sort (filter #(re-matches #"FUSEKI_DATASET_\d+" %) names))
            :when (not (contains? stain-vars v))]
      (log "WARNING:" v "is not read by this image — it is stain/jena-fuseki's."
           "Use a :datasets entry in fuseki.edn."))
    (doseq [v (sort names)
            :when (and (str/starts-with? v "FUSEKI_")
                       (not (contains? consumed v))
                       (not (contains? inherited-from-elsewhere v))
                       (not (contains? stain-vars v))
                       (not (re-matches #"FUSEKI_DATASET_\d+" v)))]
      (log "WARNING:" v "looks like ours and is not read by this image."
           "Check the spelling against the README's Environment table."))))

;; ---------------------------------------------------------------------------
;; Module-backed keys — the one place validation can't promise what it usually does
;; ---------------------------------------------------------------------------
;;
;; Every other key denotes CORE assembler vocabulary, present by definition. :text
;; denotes jena-text, which is a module: a config can be perfectly valid and still
;; unservable because the classes aren't there. Left alone, that surfaces from Jena
;; as
;;
;;   NoSpecificTypeException: the root file:///fuseki/run/config.effective.ttl#...
;;   has no most specific type that is a subclass of ja:Object
;;
;; which names a node in a file the user never wrote and explains itself via
;; ja:Object subclassing. There is no path from that back to their fuseki.edn.
;;
;; So we probe. By JAR INSPECTION, not Class.forName: Jena isn't on babashka's
;; classpath — it's handed to a separate java process at exec time — and shelling
;; out to java to find out would cost a JVM start on every boot.
(def ^:private module-classes
  {:text "org/apache/jena/query/text/assembler/TextDatasetAssembler.class"})

(defn jar-has-class?
  "true / false, or nil when the jar can't be read — which is not the same answer
  and must not be treated as one."
  [jar-path entry]
  (try
    (with-open [z (java.util.zip.ZipFile. ^String jar-path)]
      (some? (.getEntry z ^String entry)))
    (catch Exception _ nil)))

(defn check-modules!
  "Refuse a config whose module isn't in the jar, while it can still be explained
  in terms of the key someone wrote."
  [cfg]
  (doseq [[k entry] module-classes
          :when (some k (:datasets cfg))]
    (case (jar-has-class? jar entry)
      true  nil
      false (die (str k " is used by a dataset, but the Jena module that provides it"
                      " is not in " jar ". The rendered TTL would be valid and Jena"
                      " would reject it with a message about ja:Object subclassing"
                      " that names nothing you wrote — so we stop here instead."
                      " Use a mounted config.ttl, or an image that ships the module."))
      ;; Unreadable jar is a different answer from "absent", and pretending
      ;; otherwise would block a working config on our own blindness.
      (log "NOTE: could not read" jar "to confirm" k "is supported — continuing"))))

(defn- attempt
  "Run f, turning any failure into a clear single-line FATAL rather than a
  Clojure stack trace."
  [source f]
  (try (f) (catch Exception e (die (str source ": " (ex-message e))))))

(defn- render-ttl [cfg source]
  (attempt source #(render/edn->ttl cfg {:source source :tdb2-root tdb2-root})))

(defn resolve-config
  "Returns {:ttl :descr :edn :routes}. The EDN is parsed ONCE and handed back, so
  the settings it carries (:auth, :ui) come from the same value we rendered —
  reading the file twice invited the two to disagree.

  `:routes` is separate from `:edn` on purpose. It's present whenever we rendered
  the config ourselves — including the built-in default, which is exactly where
  the /ds/sparql-not-/ds/query surprise bites hardest — while `:edn` stays
  strictly \"the user mounted an EDN\", because that is what the :auth/:ui
  precedence rule keys off.

  Resolution order is in the header."
  []
  (doseq [[var path] [["FUSEKI_CONFIG" env-cfg] ["FUSEKI_EDN" env-edn-p]]
          :when (and path (not (fs/exists? path)))]
    (die var "is set to" path "but there is no file there."
         "Refusing to boot rather than silently serving the generated default"
         "— check the path and the mount."))
  (let [have-ttl (fs/exists? cfg-in)
        have-edn (fs/exists? edn-in)]
    (cond
      have-ttl
      (do (when have-edn
            (log "NOTE:" edn-in "is present but IGNORED —" cfg-in
                 "wins. A mounted config.ttl is always honoured untouched."))
          (log "config: honouring mounted" cfg-in)
          ;; No :routes — the TTL is honoured untouched and we don't parse it, so
          ;; we genuinely don't know. Saying nothing beats guessing.
          {:ttl (slurp cfg-in) :descr (str "mounted " cfg-in) :edn nil :routes nil})

      have-edn
      ;; The path goes in as well as the text: #include resolves relative to the
      ;; file that wrote it, so a config directory works wherever it's mounted.
      (let [cfg (attempt edn-in #(render/validate! (render/parse (slurp edn-in) edn-in)))]
        (check-modules! cfg)
        (log "config: rendering" edn-in "-> assembler TTL")
        {:ttl (render-ttl cfg edn-in) :descr (str "rendered from " edn-in)
         :edn cfg :routes (render/routes cfg)})

      :else
      (do (log "config: no file at" cfg-in "or" edn-in
               "— generating default in-memory dataset /" ds-name)
          {:ttl (render-ttl (default-edn) "the built-in default")
           :descr "generated default"
           :edn nil
           :routes (render/routes (default-edn))}))))

(defn resolve-setting
  "Env wins when explicitly set, then the EDN, then the default — and log which,
  because 'why is the UI off' should never need a bisect.

  `edn-path` names where in the EDN it came from, for settings whose key isn't the
  same as their name: the port lives at :server :port, so saying \"fuseki.edn
  :port\" would send a reader looking for a key that doesn't exist."
  ([what env-value from-edn default]
   (resolve-setting what env-value from-edn default what))
  ([what env-value from-edn default edn-path]
   (let [[v src] (cond env-value        [env-value "env"]
                       (some? from-edn) [from-edn (str "fuseki.edn " edn-path)]
                       :else            [default "default"])]
     (log (str (name what) ":") v (str "(from " src ")"))
     v)))

(defn ui-from-edn
  "`:ui {:enabled false}` -> \"off\". Nil when the key is absent, which is NOT the
  same as false — this key was previously validated and then ignored, so writing
  {:enabled false} still got you a UI. That's the exact 'config that lies' this
  project refuses elsewhere."
  [cfg]
  (when-let [ui (:ui cfg)]
    (if (:enabled ui) "on" "off")))

(defn resolve-shiro
  "A mounted shiro.ini is honoured untouched; otherwise generate from the
  resolved auth mode. `edn-auth` is the EDN's :auth map, if the EDN is the config
  source — it can carry :user and :password as well as :mode."
  [mode edn-auth]
  ;; Same rule as FUSEKI_CONFIG/FUSEKI_EDN above: an explicitly named shiro.ini
  ;; that isn't there would otherwise fall through to a GENERATED auth config —
  ;; quietly replacing the access rules you supplied with ours.
  (when (and env-shiro (not (fs/exists? env-shiro)))
    (die "FUSEKI_SHIRO is set to" env-shiro "but there is no file there."
         "Refusing to boot rather than silently generating auth rules instead."))
  (if (fs/exists? shiro-in)
    (do (log "shiro: honouring mounted" shiro-in "(generated auth settings not used)")
        (slurp shiro-in))
    (do
      (log "shiro: generating" (str "'" mode "'") "config")
      (case mode
        "anon"  render/shiro-anon
        "basic" (let [user      (or (System/getenv "FUSEKI_ADMIN_USER")
                                    (:user edn-auth)
                                    "admin")
                      [pw src]  (read-secret (:password edn-auth))]
                  (when-not pw
                    (die (str "auth is 'basic' but no password was given. Set "
                              "FUSEKI_ADMIN_PASSWORD, or FUSEKI_ADMIN_PASSWORD_FILE, or "
                              ":auth {:password #env \"...\"} in fuseki.edn.")))
                  ;; Source, never the value.
                  (log "auth: basic, user" user "— secret from" src)
                  (attempt "auth" #(render/shiro-basic user pw)))
        (die "auth must be 'anon' or 'basic', got:" mode)))))

(defn -main []
  (fs/create-dirs base)
  ;; Before anything is resolved, so a migrator sees "that variable did nothing"
  ;; above the lines showing what happened instead.
  (report-unused-env!)
  (let [eff-cfg   (str base "/config.effective.ttl")
        eff-shiro (str base "/shiro.ini")   ; Fuseki discovers shiro.ini in FUSEKI_BASE
        eff-port  (str base "/port")        ; read by the image's HEALTHCHECK
        {:keys [ttl descr edn routes]} (resolve-config)
        ;; Both settings come from the ONE parsed config above. They only apply
        ;; when the EDN is the config source — a mounted config.ttl means the EDN
        ;; was ignored wholesale, and half-honouring an ignored file would be
        ;; worse than ignoring it.
        auth (resolve-setting :auth env-auth (some-> edn :auth :mode name) auth-default)
        ui   (resolve-setting :ui   env-ui   (ui-from-edn edn)             ui-default)
        ;; `str` because the EDN carries an integer and everything downstream —
        ;; the --port= argument, the healthcheck file — is text.
        port (resolve-setting :port env-port (some-> edn :server :port str)
                              port-default ":server :port")
        shiro (resolve-shiro auth (:auth edn))]
    (spit eff-cfg ttl)
    (spit eff-shiro shiro)
    ;; The effective PORT, written where the healthcheck can read it. Same
    ;; principle as the config and shiro above: what actually took effect is on
    ;; disk. Without this, a port set in fuseki.edn boots fine and is then
    ;; reported unhealthy, because HEALTHCHECK only knows the env var.
    (spit eff-port port)
    (log "effective config ->" eff-cfg (str "(" descr ")"))
    ;; Routes are decisions too. `:endpoints #{:query}` serves /ds/sparql because
    ;; that is Fuseki's conventional name — printing the paths turns that from a
    ;; 404 you have to go and discover into a line you already read at boot.
    ;; Grouped by operation: "what did it wire up" needs the verb as well as the
    ;; path. A bare "/x" is true and useless when /x serves query, update and
    ;; gsp-rw at once.
    (doseq [[ds ops] routes]
      (log "routes:" ds "->"
           (str/join " | " (for [[op paths] ops]
                             (str (name op) " " (str/join " " paths))))))
    (log "effective shiro  ->" eff-shiro "(secrets not logged)")
    (log "effective port   ->" eff-port (str "(" port ")"))
    (let [launch (case ui
                   "on"  ["java" "-jar" jar]
                   "off" ["java" "-cp" jar plain-main]
                   (die "ui must be 'on' or 'off', got:" ui))
          ;; ONE vector, logged and executed. `log` is println with varargs, so
          ;; the previous line printed "--port= 3030  --config= ..." — an argv you
          ;; could not paste, on the one line whose whole job is telling you what
          ;; ran. It also said "fuseki-server", which is neither `java -jar` nor
          ;; `java -cp ... FusekiServerPlainCmd`.
          args   (into launch [(str "--port=" port) (str "--config=" eff-cfg)])]
      (log "ui mode:" (if (= ui "off") "headless — no UI, no admin area" "Fuseki's own UI + admin area"))
      (log "exec:" (str/join " " args))
      ;; exec (not run) so Fuseki is PID 1's child with clean signal handling.
      (p/exec args))))

(-main)
