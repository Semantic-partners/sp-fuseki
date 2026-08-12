#!/usr/bin/env bb
;; sp-fuseki entrypoint — legible boot orchestration.
;;
;; This is the contract. Every knob below is documented in the README; nothing
;; here is magic you have to reverse-engineer. The job: resolve config + shiro,
;; write the *effective* files where you can read them, then exec Fuseki.
;;
;; Environment (the extension points):
;;   FUSEKI_BASE                  runtime/data dir              (default /fuseki/run)
;;   FUSEKI_CONFIG                assembler config.ttl          (default /fuseki/config.ttl)
;;   FUSEKI_SHIRO                 shiro.ini                     (default /fuseki/shiro.ini)
;;   FUSEKI_PORT                  listen port                   (default 3030)
;;   FUSEKI_DATASET               default ds name (no config)   (default ds)
;;   FUSEKI_AUTH                  anon | basic                  (default anon)
;;   FUSEKI_ADMIN_USER            basic-auth user               (default admin)
;;   FUSEKI_ADMIN_PASSWORD        basic-auth secret (env)
;;   FUSEKI_ADMIN_PASSWORD_FILE   basic-auth secret (file; e.g. a Docker secret)
;;   FUSEKI_UI                    on | off                      (default on)
;;   FUSEKI_JAR                   fuseki-server.jar             (default /opt/fuseki/fuseki-server.jar)
;;
;; Config resolution (config-respecting, never silently regenerated):
;;   - FUSEKI_CONFIG mounted  -> honoured untouched
;;   - otherwise              -> a minimal in-memory dataset is generated
;;   - the effective config + shiro are always written under FUSEKI_BASE and the
;;     paths logged, so "what did it actually run" is never a mystery.

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])

(defn env [k d] (or (System/getenv k) d))
(defn log [& xs] (binding [*out* *err*] (apply println "[sp-fuseki]" xs)))
(defn die [& xs]
  (binding [*out* *err*] (apply println "[sp-fuseki] FATAL:" xs))
  (System/exit 1))

(def base     (env "FUSEKI_BASE"    "/fuseki/run"))
(def cfg-in   (env "FUSEKI_CONFIG"  "/fuseki/config.ttl"))
(def shiro-in (env "FUSEKI_SHIRO"   "/fuseki/shiro.ini"))
(def port     (env "FUSEKI_PORT"    "3030"))
(def ds-name  (env "FUSEKI_DATASET" "ds"))
(def auth     (env "FUSEKI_AUTH"    "anon"))
(def ui       (env "FUSEKI_UI"      "on"))
(def jar      (env "FUSEKI_JAR"     "/opt/fuseki/fuseki-server.jar"))

;; The one jar carries both servers. Its manifest Main-Class is the UI + admin
;; build (what `java -jar` gets you); this is the headless one, same class the
;; dist's own `fuseki-plain` script selects. So FUSEKI_UI is a runtime choice —
;; no second image, no extra build leg.
(def plain-main "org.apache.jena.fuseki.main.cmds.FusekiServerPlainCmd")

(defn default-config []
  (format "# sp-fuseki: GENERATED default — no config mounted at %s.
# One in-memory dataset '/%s' with query + update + gsp-rw. Mount your own
# config.ttl (at FUSEKI_CONFIG) to override; it will be honoured untouched.
@prefix fuseki: <http://jena.apache.org/fuseki#> .
@prefix ja:     <http://jena.hpl.hp.com/2005/11/Assembler#> .

[] a fuseki:Service ;
   fuseki:name \"%s\" ;
   fuseki:endpoint [ fuseki:operation fuseki:query  ; fuseki:name \"sparql\" ] ;
   fuseki:endpoint [ fuseki:operation fuseki:update ; fuseki:name \"update\" ] ;
   fuseki:endpoint [ fuseki:operation fuseki:gsp-rw ; fuseki:name \"data\" ] ;
   fuseki:dataset [ a ja:RDFDataset ; ja:defaultGraph [ a ja:MemoryModel ] ] .
" cfg-in ds-name ds-name))

(defn read-secret []
  (let [pw  (System/getenv "FUSEKI_ADMIN_PASSWORD")
        pwf (System/getenv "FUSEKI_ADMIN_PASSWORD_FILE")]
    (cond
      (and pwf (fs/exists? pwf)) (str/trim (slurp pwf))
      (and pwf (not (fs/exists? pwf))) (die "FUSEKI_ADMIN_PASSWORD_FILE set but not found:" pwf)
      pw pw
      :else nil)))

(def shiro-anon
  "# sp-fuseki: GENERATED — anonymous (throwaway/lab). NOT for exposed use.
#
# Data endpoints are open; the MUTATING admin API is not. `POST /$/datasets`
# creates datasets and `DELETE /$/datasets/{name}` drops them, so leaving all of
# /$/ anonymous means anyone who can reach the port can delete your data. Shiro
# matches on path, not method, so fencing the mutation means fencing the path:
# with no [users] defined, authcBasic is a permanent 401 and admin is simply
# closed. Set FUSEKI_AUTH=basic to actually use the admin API.
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

(defn shiro-basic []
  (let [user (env "FUSEKI_ADMIN_USER" "admin")
        pw   (read-secret)]
    (when-not pw
      (die "FUSEKI_AUTH=basic but no FUSEKI_ADMIN_PASSWORD or FUSEKI_ADMIN_PASSWORD_FILE."))
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
" user pw)))

(defn resolve-config []
  (if (fs/exists? cfg-in)
    (do (log "config: honouring mounted" cfg-in) (slurp cfg-in))
    (do (log "config: no file at" cfg-in "— generating default in-memory dataset /" ds-name)
        (default-config))))

(defn resolve-shiro []
  (if (fs/exists? shiro-in)
    (do (log "shiro: honouring mounted" shiro-in) (slurp shiro-in))
    (do (log "shiro: generating" (str "'" auth "'") "config")
        (case auth
          "anon"  shiro-anon
          "basic" (shiro-basic)
          (die "FUSEKI_AUTH must be 'anon' or 'basic', got:" auth)))))

(defn -main []
  (fs/create-dirs base)
  (let [eff-cfg   (str base "/config.effective.ttl")
        eff-shiro (str base "/shiro.ini")        ; Fuseki discovers shiro.ini in FUSEKI_BASE
        cfg       (resolve-config)
        shiro     (resolve-shiro)]
    (spit eff-cfg cfg)
    (spit eff-shiro shiro)
    (log "effective config ->" eff-cfg)
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
