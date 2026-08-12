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
;;   FUSEKI_ADMIN_USER            basic-auth user               (default admin)
;;   FUSEKI_ADMIN_PASSWORD        basic-auth secret (env)
;;   FUSEKI_ADMIN_PASSWORD_FILE   basic-auth secret (file; e.g. a Docker secret)
;;   FUSEKI_UI                    on | off                      (default on)
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
(def auth      (env "FUSEKI_AUTH"       "anon"))
(def ui        (env "FUSEKI_UI"         "on"))
(def tdb2-root (env "FUSEKI_TDB2_ROOT"  "/fuseki/databases"))
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

(defn read-secret []
  (let [pw  (System/getenv "FUSEKI_ADMIN_PASSWORD")
        pwf (System/getenv "FUSEKI_ADMIN_PASSWORD_FILE")]
    (cond
      (and pwf (fs/exists? pwf)) (str/trim (slurp pwf))
      (and pwf (not (fs/exists? pwf))) (die "FUSEKI_ADMIN_PASSWORD_FILE set but not found:" pwf)
      pw pw
      :else nil)))

(defn render-edn
  "Parse + validate + render an EDN config, turning any failure into a clear,
  single-line FATAL rather than a Clojure stack trace."
  [text source]
  (try
    (render/edn->ttl (render/parse text) {:source source :tdb2-root tdb2-root})
    (catch Exception e
      (die (str source ": " (ex-message e))))))

(defn resolve-config
  "Returns [ttl description]. See the resolution order in the header."
  []
  (let [have-ttl (fs/exists? cfg-in)
        have-edn (fs/exists? edn-in)]
    (cond
      have-ttl
      (do (when have-edn
            (log "NOTE:" edn-in "is present but IGNORED —" cfg-in
                 "wins. A mounted config.ttl is always honoured untouched."))
          (log "config: honouring mounted" cfg-in)
          [(slurp cfg-in) (str "mounted " cfg-in)])

      have-edn
      (do (log "config: rendering" edn-in "-> assembler TTL")
          [(render-edn (slurp edn-in) edn-in) (str "rendered from " edn-in)])

      :else
      (do (log "config: no file at" cfg-in "or" edn-in
               "— generating default in-memory dataset /" ds-name)
          [(render/edn->ttl (default-edn) {:source "the built-in default"
                                           :tdb2-root tdb2-root})
           "generated default"]))))

(defn resolve-shiro
  "Auth from a mounted shiro.ini, else generated from FUSEKI_AUTH. The EDN's
  :auth key is honoured too, but FUSEKI_AUTH wins if explicitly set — env beats
  file for the same reason secrets do."
  [cfg-auth]
  (if (fs/exists? shiro-in)
    (do (log "shiro: honouring mounted" shiro-in) (slurp shiro-in))
    (let [mode (or (when (System/getenv "FUSEKI_AUTH") auth)
                   (some-> cfg-auth name)
                   auth)]
      (log "shiro: generating" (str "'" mode "'") "config")
      (case mode
        "anon"  render/shiro-anon
        "basic" (let [user (env "FUSEKI_ADMIN_USER" "admin")
                      pw   (read-secret)]
                  (when-not pw
                    (die "auth is 'basic' but no FUSEKI_ADMIN_PASSWORD or FUSEKI_ADMIN_PASSWORD_FILE."))
                  (try (render/shiro-basic user pw)
                       (catch Exception e (die (ex-message e)))))
        (die "auth must be 'anon' or 'basic', got:" mode)))))

(defn -main []
  (fs/create-dirs base)
  (let [eff-cfg        (str base "/config.effective.ttl")
        eff-shiro      (str base "/shiro.ini")   ; Fuseki discovers shiro.ini in FUSEKI_BASE
        [cfg descr]    (resolve-config)
        ;; :auth from the EDN only applies when the EDN is the config source.
        edn-auth       (when (and (fs/exists? edn-in) (not (fs/exists? cfg-in)))
                         (try (:mode (:auth (render/parse (slurp edn-in)))) (catch Exception _ nil)))
        shiro          (resolve-shiro edn-auth)]
    (spit eff-cfg cfg)
    (spit eff-shiro shiro)
    (log "effective config ->" eff-cfg (str "(" descr ")"))
    (log "effective shiro  ->" eff-shiro "(secrets not logged)")
    (let [launch (case ui
                   "on"  ["java" "-jar" jar]
                   "off" ["java" "-cp" jar plain-main]
                   (die "FUSEKI_UI must be 'on' or 'off', got:" ui))]
      (log "ui:" ui (if (= ui "off") "(headless — no UI, no admin area)" "(Fuseki's own UI + admin area)"))
      (log "exec: fuseki-server --port=" port " --config=" eff-cfg)
      ;; exec (not run) so Fuseki is PID 1's child with clean signal handling.
      (p/exec (into launch [(str "--port=" port) (str "--config=" eff-cfg)])))))

(-main)
