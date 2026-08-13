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
(def cfg-in    (env "FUSEKI_CONFIG"     "/fuseki/config.ttl"))
(def edn-in    (env "FUSEKI_EDN"        "/fuseki/fuseki.edn"))
(def shiro-in  (env "FUSEKI_SHIRO"      "/fuseki/shiro.ini"))
(def port      (env "FUSEKI_PORT"       "3030"))
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

(defn- attempt
  "Run f, turning any failure into a clear single-line FATAL rather than a
  Clojure stack trace."
  [source f]
  (try (f) (catch Exception e (die (str source ": " (ex-message e))))))

(defn- render-ttl [cfg source]
  (attempt source #(render/edn->ttl cfg {:source source :tdb2-root tdb2-root})))

(defn resolve-config
  "Returns {:ttl :descr :edn}. The EDN is parsed ONCE and handed back, so the
  settings it carries (:auth, :ui) come from the same value we rendered — reading
  the file twice invited the two to disagree.

  Resolution order is in the header."
  []
  (let [have-ttl (fs/exists? cfg-in)
        have-edn (fs/exists? edn-in)]
    (cond
      have-ttl
      (do (when have-edn
            (log "NOTE:" edn-in "is present but IGNORED —" cfg-in
                 "wins. A mounted config.ttl is always honoured untouched."))
          (log "config: honouring mounted" cfg-in)
          {:ttl (slurp cfg-in) :descr (str "mounted " cfg-in) :edn nil})

      have-edn
      (let [cfg (attempt edn-in #(render/validate! (render/parse (slurp edn-in))))]
        (log "config: rendering" edn-in "-> assembler TTL")
        {:ttl (render-ttl cfg edn-in) :descr (str "rendered from " edn-in) :edn cfg})

      :else
      (do (log "config: no file at" cfg-in "or" edn-in
               "— generating default in-memory dataset /" ds-name)
          {:ttl (render-ttl (default-edn) "the built-in default")
           :descr "generated default"
           :edn nil}))))

(defn resolve-setting
  "Env wins when explicitly set, then the EDN, then the default — and log which,
  because 'why is the UI off' should never need a bisect."
  [what env-value from-edn default]
  (let [[v src] (cond env-value        [env-value "env"]
                      (some? from-edn) [from-edn (str "fuseki.edn " what)]
                      :else            [default "default"])]
    (log (str (name what) ":") v (str "(from " src ")"))
    v))

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
  (let [eff-cfg   (str base "/config.effective.ttl")
        eff-shiro (str base "/shiro.ini")   ; Fuseki discovers shiro.ini in FUSEKI_BASE
        {:keys [ttl descr edn]} (resolve-config)
        ;; Both settings come from the ONE parsed config above. They only apply
        ;; when the EDN is the config source — a mounted config.ttl means the EDN
        ;; was ignored wholesale, and half-honouring an ignored file would be
        ;; worse than ignoring it.
        auth (resolve-setting :auth env-auth (some-> edn :auth :mode name) auth-default)
        ui   (resolve-setting :ui   env-ui   (ui-from-edn edn)             ui-default)
        shiro (resolve-shiro auth (:auth edn))]
    (spit eff-cfg ttl)
    (spit eff-shiro shiro)
    (log "effective config ->" eff-cfg (str "(" descr ")"))
    (log "effective shiro  ->" eff-shiro "(secrets not logged)")
    (let [launch (case ui
                   "on"  ["java" "-jar" jar]
                   "off" ["java" "-cp" jar plain-main]
                   (die "ui must be 'on' or 'off', got:" ui))]
      (log "ui mode:" (if (= ui "off") "headless — no UI, no admin area" "Fuseki's own UI + admin area"))
      (log "exec: fuseki-server --port=" port " --config=" eff-cfg)
      ;; exec (not run) so Fuseki is PID 1's child with clean signal handling.
      (p/exec (into launch [(str "--port=" port) (str "--config=" eff-cfg)])))))

(-main)
