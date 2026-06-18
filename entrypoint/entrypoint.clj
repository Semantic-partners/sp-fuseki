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
(def jar      (env "FUSEKI_JAR"     "/opt/fuseki/fuseki-server.jar"))

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
[main]
ssl.enabled = false
[users]
[roles]
[urls]
/** = anon
")

(defn shiro-basic []
  (let [user (env "FUSEKI_ADMIN_USER" "admin")
        pw   (read-secret)]
    (when-not pw
      (die "FUSEKI_AUTH=basic but no FUSEKI_ADMIN_PASSWORD or FUSEKI_ADMIN_PASSWORD_FILE."))
    (format "# sp-fuseki: GENERATED — basic auth, all endpoints require login.
[main]
ssl.enabled = false
[users]
%s = %s
[roles]
[urls]
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
    (log "exec: fuseki-server --port=" port " --config=" eff-cfg)
    ;; exec (not run) so Fuseki is PID 1's child with clean signal handling.
    (p/exec ["java" "-jar" jar (str "--port=" port) (str "--config=" eff-cfg)])))

(-main)
