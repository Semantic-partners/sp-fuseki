#!/usr/bin/env bb
(ns smoke
  "sp-fuseki packaging smoke tests — the container's behaviour, not Jena's
  correctness (that's Apache's job).

  Ported from 531 lines of bash. Four defects in that bash, none of them logic
  errors, all found in a single day:

    - `docker logs | grep -q` SIGPIPEs docker, so `pipefail` failed a test whose
      assertion had actually SUCCEEDED
    - `$(printf '\\n')` loses its newline to command substitution, so a test meant
      to reject an untrimmed secret sent the valid one and passed for the wrong
      reason
    - `grep -qv` is vacuously true (it succeeds if ANY line lacks the pattern), so
      a check that the secret wasn't logged would have passed while leaking it
    - container teardown printed `No such container` on green runs

  None of those are expressible here: a string containing a newline is just a
  string, HTTP status codes are integers rather than `-w '%{http_code}'` output,
  and `clojure.test` reports each assertion instead of stopping at the first
  `exit 1`.

  Usage: IMAGE=sp-fuseki:dev bash test/smoke.sh   (wrapper finds bb)
         IMAGE=sp-fuseki:dev bb test/smoke.clj"
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is run-tests]]))

(def image (or (System/getenv "IMAGE") "sp-fuseki:dev"))
(def host-port (or (System/getenv "PORT") "13030"))
(def here (str (fs/parent (fs/absolutize *file*))))
(def repo (str (fs/parent here)))
(def base (str "http://localhost:" host-port))
(def volume (str "sp-fuseki-smoke-" host-port))   ; predictable, force-removed either side
(def tmp (str (fs/create-temp-dir {:prefix "sp-fuseki-smoke"})))

;; ---------------------------------------------------------------------------
;; docker + http
;; ---------------------------------------------------------------------------

(defn- docker [& args]
  (apply p/shell {:out :string :err :string :continue true} "docker" args))

(defn- rm! [cid]
  (when cid (docker "rm" "-f" cid)))

(defn- start
  "docker run -d with `args`, returning the container id. Publishes host-port to
  `cport` inside (default 3030)."
  [{:keys [env mounts cport args] :or {cport "3030"}}]
  (let [flags (concat ["run" "-d" "-p" (str host-port ":" cport)]
                      (mapcat (fn [[k v]] ["-e" (str k "=" v)]) env)
                      (mapcat (fn [m] ["-v" m]) mounts)
                      args
                      [image])
        {:keys [out err exit]} (apply docker flags)]
    (when-not (zero? exit)
      (throw (ex-info (str "docker run failed: " err) {})))
    (str/trim out)))

(defmacro with-container
  "Starts a container, always removes it. Replaces the bash CIDS array and `drop`,
  whose bookkeeping printed 'No such container' on passing runs."
  [[sym opts] & body]
  `(let [~sym (start ~opts)]
     (try ~@body (finally (rm! ~sym)))))

(defn- status
  "HTTP status, or nil if the connection failed. No %{http_code} parsing."
  [url & [opts]]
  (try (:status (http/get url (merge {:throw false :timeout 5000} opts)))
       (catch Exception _ nil)))

(defn- GET [url & [opts]]
  (http/get url (merge {:throw false :timeout 10000} opts)))

(defn- ask
  "Run an ASK and return the boolean Fuseki reports."
  [url query & [opts]]
  (let [{:keys [status body]} (GET url (merge {:query-params {"query" query}
                                               :headers {"Accept" "application/sparql-results+json"}}
                                              opts))]
    (and (= 200 status) (some? (re-find #"\"boolean\"\s*:\s*true" (str body))))))

(defn- post-ttl [url file & [opts]]
  (http/post url (merge {:body (slurp file)
                         :headers {"Content-Type" "text/turtle"}
                         :throw false :timeout 10000}
                        opts)))

(defn- wait-ping []
  (loop [n 60]
    (cond
      (= 200 (status (str base "/$/ping"))) true
      (zero? n) (throw (ex-info (str "server did not answer /$/ping at " base " within 60s") {}))
      :else (do (Thread/sleep 1000) (recur (dec n))))))

(defn- health
  "Docker's OWN healthcheck verdict. Two bugs in two days were 'serves fine,
  reported unhealthy' — basic auth gating /$/ping, and the healthcheck not knowing
  an EDN-supplied port — so this asks docker rather than reimplementing the probe."
  [cid]
  (loop [n 45]
    (let [st (str/trim (:out (docker "inspect" "--format" "{{.State.Health.Status}}" cid)))]
      (cond
        (= "healthy" st)   :healthy
        (= "unhealthy" st) :unhealthy
        (zero? n)          (keyword (str "timeout-" st))
        :else (do (Thread/sleep 2000) (recur (dec n)))))))

(defn- logs [cid] (str (:out (docker "logs" cid)) (:err (docker "logs" cid))))

(defn- exec-ok? [cid & cmd]
  (zero? (:exit (apply docker "exec" cid cmd))))

(defn- boot-output
  "Run to completion and capture output — for the configs that must FATAL."
  [{:keys [env mounts]}]
  (let [flags (concat ["run" "--rm"]
                      (mapcat (fn [[k v]] ["-e" (str k "=" v)]) env)
                      (mapcat (fn [m] ["-v" m]) mounts)
                      [image])
        {:keys [out err]} (apply docker flags)]
    (str out err)))

(defn- fixture [name content]
  ;; Parents created, so a fixture can be nested — #include's whole point is a
  ;; config directory, which needs one.
  (let [f (str tmp "/" name)] (fs/create-dirs (fs/parent f)) (spit f content) f))

(def sample-ttl (str here "/sample.ttl"))
(def inference-ttl (str here "/inference.ttl"))
(def config-ttl (str repo "/examples/config.ttl"))
(def config-tdb2 (str repo "/examples/config-tdb2.ttl"))
(def example-edn (str repo "/examples/fuseki.edn"))

;; ---------------------------------------------------------------------------
;; 1-3. the basics
;; ---------------------------------------------------------------------------

(deftest s01-runs-non-root
  (let [{:keys [out]} (docker "run" "--rm" "--entrypoint" "id" image "-u")]
    (is (= "1000" (str/trim out)) "must run as uid 1000")))

(deftest s02-03-zero-config-boot-and-round-trip
  (with-container [cid {}]
    (wait-ping)
    (is (= 200 (status (str base "/$/ping"))) "/$/ping answered")
    (is (= 200 (:status (post-ttl (str base "/ds/data?default") sample-ttl)))
        "POST turtle to the generated default dataset")
    (is (ask (str base "/ds/sparql")
             "ASK { <http://example.org/s> <http://example.org/p> <http://example.org/o> }")
        "round-trip confirmed")))

(deftest s04-mounted-config-is-honoured-not-merged
  (with-container [cid {:mounts [(str config-ttl ":/fuseki/config.ttl:ro")]}]
    (wait-ping)
    (is (ask (str base "/training/sparql") "ASK {}") "/training present (mounted config used)")
    (is (= 404 (status (str base "/ds/sparql?query=ASK%20%7B%7D")))
        "generated default /ds absent — config respected, not merged")))

;; ---------------------------------------------------------------------------
;; 5-7. UI, admin fencing, headless
;; ---------------------------------------------------------------------------

(deftest s05-fuseki-ui-is-served
  (with-container [cid {}]
    (wait-ping)
    (let [{st :status body :body} (GET (str base "/"))
          asset (second (re-find #"(static/[A-Za-z0-9._-]+\.js)" (str body)))]
      (is (and (= 200 st) (str/includes? (str body) "Apache Jena Fuseki UI"))
          "/ serves the Fuseki UI shell")
      ;; The shell is useless if its bundle 404s.
      (is (and asset (= 200 (status (str base "/" asset))))
          (str "UI bundle served: " asset))
      (is (str/includes? (str (:body (GET (str base "/$/server")))) "\"datasets\"")
          "/$/server reports datasets"))))

(deftest s06-anon-mode-fences-the-mutating-admin-api
  (with-container [cid {}]
    (wait-ping)
    (is (= 401 (:status (http/post (str base "/$/datasets")
                                   {:body "dbName=smoke-should-not-exist&dbType=mem"
                                    :throw false :timeout 10000})))
        "POST /$/datasets -> 401 (admin API must not be open)")
    (is (= 401 (:status (http/delete (str base "/$/datasets/ds") {:throw false :timeout 10000})))
        "DELETE /$/datasets/ds -> 401 (your data must not be deletable)")
    (is (ask (str base "/ds/sparql") "ASK {}") "data endpoints still anonymous")))

(deftest s07-ui-off-is-headless-but-still-serves-data
  (with-container [cid {:env {"FUSEKI_UI" "off"}}]
    (wait-ping)
    (is (not= 200 (status (str base "/"))) "/ not served with FUSEKI_UI=off")
    (post-ttl (str base "/ds/data?default") sample-ttl)
    (is (ask (str base "/ds/sparql")
             "ASK { <http://example.org/s> <http://example.org/p> <http://example.org/o> }")
        "headless round-trip confirmed")))

;; ---------------------------------------------------------------------------
;; 8. TDB2 on the documented mount
;; ---------------------------------------------------------------------------

(deftest s08-tdb2-survives-a-restart
  (docker "volume" "rm" "-f" volume)
  (with-container [cid {:mounts [(str volume ":/fuseki/databases")
                                 (str config-tdb2 ":/fuseki/config.ttl:ro")]}]
    (try
      (wait-ping)
      (is (= 200 (status (str base "/$/ping"))) "booted with a volume mounted at /fuseki/databases")
      ;; A root-owned mount point would have died before this with a misleading
      ;; "No such file or directory", so a successful write IS the assertion.
      (is (= 200 (:status (post-ttl (str base "/ds/data?default") sample-ttl)))
          "wrote to the TDB2 dataset")
      (docker "restart" cid)
      (wait-ping)
      (is (ask (str base "/ds/sparql")
               "ASK { <http://example.org/s> <http://example.org/p> <http://example.org/o> }")
          "data survived a container restart")
      (finally (docker "volume" "rm" "-f" volume)))))

;; ---------------------------------------------------------------------------
;; 9-11. the EDN path
;; ---------------------------------------------------------------------------

(deftest s09-fuseki-edn-renders-to-ttl
  (docker "volume" "rm" "-f" volume)
  (with-container [cid {:mounts [(str example-edn ":/fuseki/fuseki.edn:ro")
                                 (str volume ":/fuseki/databases")]}]
    (try
      (wait-ping)
      (is (every? #(ask (str base "/" % "/sparql") "ASK {}") ["training" "kb" "training-inferred"])
          "all three datasets served (mem, tdb2, reasoner)")
      (is (and (exec-ok? cid "sh" "-c" "grep -q 'GENERATED from' /fuseki/run/config.effective.ttl")
               (exec-ok? cid "sh" "-c" "grep -q 'tdb2:location \"/fuseki/databases/kb\"' /fuseki/run/config.effective.ttl"))
          "effective TTL written, tdb2 location under /fuseki/databases")
      (post-ttl (str base "/training-inferred/data?default") inference-ttl)
      (is (ask (str base "/training-inferred/sparql")
               "ASK { <http://example.org/socrates> a <http://example.org/Mortal> }")
          "RDFS reasoner entails rdfs:subClassOf (inference is real)")
      (finally (docker "volume" "rm" "-f" volume)))))

(deftest s10-mounted-ttl-beats-mounted-edn-loudly
  (with-container [cid {:mounts [(str config-ttl ":/fuseki/config.ttl:ro")
                                 (str example-edn ":/fuseki/fuseki.edn:ro")]}]
    (wait-ping)
    (is (and (ask (str base "/training/sparql") "ASK {}")
             (= 404 (status (str base "/kb/sparql?query=ASK%20%7B%7D"))))
        "TTL won; EDN-only dataset /kb absent")
    ;; `docker logs | grep -q` SIGPIPEd docker and tripped pipefail on a SUCCESSFUL
    ;; match. Here the logs are a string.
    (is (str/includes? (logs cid) "IGNORED")
        "ignoring the EDN was logged — conflicting config must never be silent")))

(deftest s11-broken-edn-is-fatal-at-boot
  (let [out (boot-output {:mounts [(str (fixture "bad.edn"
                                                 "{:datasets [{:name \"bad/name\" :storage :mem :endpoints #{:query}}]}")
                                        ":/fuseki/fuseki.edn:ro")]})]
    (is (and (str/includes? out "FATAL") (str/includes? out "URL path segment"))
        "invalid EDN -> FATAL with an actionable message, no half-configured boot")))

;; ---------------------------------------------------------------------------
;; 12-13. settings the EDN carries are honoured, not merely validated
;; ---------------------------------------------------------------------------

(def ui-off-edn
  (delay (fixture "ui-off.edn"
                  "{:ui {:enabled false}\n :datasets [{:name \"ds\" :storage :mem :endpoints #{:query :gsp-rw}}]}")))

(deftest s12-edn-ui-false-actually-disables-the-ui
  (with-container [cid {:mounts [(str @ui-off-edn ":/fuseki/fuseki.edn:ro")]}]
    (wait-ping)
    (is (not= 200 (status (str base "/"))) "EDN :ui {:enabled false} disables the UI")
    (is (ask (str base "/ds/sparql") "ASK {}") "data endpoints unaffected")
    (is (re-find #"ui: off \(from fuseki\.edn" (logs cid))
        "resolved value logged with its source")))

(deftest s13-explicit-env-beats-the-edn
  (with-container [cid {:env {"FUSEKI_UI" "on"}
                        :mounts [(str @ui-off-edn ":/fuseki/fuseki.edn:ro")]}]
    (wait-ping)
    (is (str/includes? (str (:body (GET (str base "/")))) "Apache Jena Fuseki UI")
        "env overrides the file, as documented")))

;; ---------------------------------------------------------------------------
;; 14-18. credentials. Both CVEs found in Jena 6.1.0 were in the auth path.
;; ---------------------------------------------------------------------------

(deftest s14-basic-auth-and-the-healthcheck
  (with-container [cid {:env {"FUSEKI_AUTH" "basic" "FUSEKI_ADMIN_PASSWORD" "s3cret"}}]
    (wait-ping)                                   ; /$/ping is deliberately anon
    (is (= 401 (status (str base "/ds/sparql?query=ASK%20%7B%7D")))
        "unauthenticated query -> 401")
    (post-ttl (str base "/ds/data?default") sample-ttl {:basic-auth ["admin" "s3cret"]})
    (is (ask (str base "/ds/sparql")
             "ASK { <http://example.org/s> <http://example.org/p> <http://example.org/o> }"
             {:basic-auth ["admin" "s3cret"]})
        "authenticated round-trip confirmed")
    ;; The image's own HEALTHCHECK command. Gating /$/ping made every basic-auth
    ;; container report unhealthy, and nothing caught it until it was fixed.
    (is (exec-ok? cid "sh" "-c" "curl -fsS \"http://localhost:3030/$/ping\" >/dev/null")
        "HEALTHCHECK command still succeeds under basic auth")))

(deftest s15-password-from-a-file
  (let [pw (fixture "pw" "filesecret\n")]        ; trailing newline on purpose
    (with-container [cid {:env {"FUSEKI_AUTH" "basic"
                                "FUSEKI_ADMIN_PASSWORD_FILE" "/run/secrets/pw"}
                          :mounts [(str pw ":/run/secrets/pw:ro")]}]
      (wait-ping)
      (is (ask (str base "/ds/sparql") "ASK {}" {:basic-auth ["admin" "filesecret"]})
          "secret read from file, newline trimmed")
      ;; In bash this sent the VALID credential, because `$(printf '\n')` loses the
      ;; newline to command substitution. Here the newline is just a character.
      (is (= 401 (:status (GET (str base "/ds/sparql?query=ASK%20%7B%7D")
                               {:basic-auth ["admin" "filesecret\n"]})))
          "the untrimmed form is rejected"))))

(deftest s16-a-missing-secret-file-is-fatal
  (let [out (boot-output {:env {"FUSEKI_AUTH" "basic"
                                "FUSEKI_ADMIN_PASSWORD_FILE" "/run/secrets/nope"}})]
    (is (and (str/includes? out "FATAL") (str/includes? out "not found"))
        "missing secret file -> FATAL naming the path, not an empty password")))

(deftest s17-edn-can-carry-the-credential
  (let [edn (fixture "auth.edn"
                     (str "{:auth {:mode :basic :user \"carol\" :password #env \"SP_SECRET\"}\n"
                          " :datasets [{:name \"ds\" :storage :mem :endpoints #{:query :gsp-rw}}]}"))]
    (with-container [cid {:env {"SP_SECRET" "fromenvtag"}
                          :mounts [(str edn ":/fuseki/fuseki.edn:ro")]}]
      (wait-ping)
      (is (ask (str base "/ds/sparql") "ASK {}" {:basic-auth ["carol" "fromenvtag"]})
          "EDN-supplied user + #env secret authenticate")
      (is (= 401 (:status (GET (str base "/ds/sparql?query=ASK%20%7B%7D")
                               {:basic-auth ["admin" "fromenvtag"]})))
          "EDN :user honoured, not the default")
      (let [l (logs cid)]
        ;; `grep -qv` was vacuously true here and would have passed while leaking.
        (is (and (str/includes? l "secret from fuseki.edn")
                 (not (str/includes? l "fromenvtag")))
            "source logged, secret value absent from the log")))))

(deftest s18-a-mounted-shiro-is-honoured-untouched
  (let [ini (fixture "shiro.ini"
                     (str "[main]\nssl.enabled = false\n[users]\ndave = mountedpw\n"
                          "[roles]\n[urls]\n/$/ping = anon\n/** = authcBasic\n"))]
    (with-container [cid {:env {"FUSEKI_AUTH" "basic" "FUSEKI_ADMIN_PASSWORD" "ignored"}
                          :mounts [(str ini ":/fuseki/shiro.ini:ro")]}]
      (wait-ping)
      (is (ask (str base "/ds/sparql") "ASK {}" {:basic-auth ["dave" "mountedpw"]})
          "the mounted file's user authenticates")
      (is (= 401 (:status (GET (str base "/ds/sparql?query=ASK%20%7B%7D")
                               {:basic-auth ["admin" "ignored"]})))
          "generated credentials absent — honoured untouched, not merged"))))

;; ---------------------------------------------------------------------------
;; 19-22. the port, and the boot log
;; ---------------------------------------------------------------------------

(def alt-port "8080")

(deftest s19-fuseki-port-moves-the-listener-and-the-healthcheck
  (with-container [cid {:env {"FUSEKI_PORT" alt-port} :cport alt-port}]
    (wait-ping)
    (is (= 200 (status (str base "/$/ping")))
        (str "listening on " alt-port " (host " host-port ")"))
    (is (= 200 (:status (post-ttl (str base "/ds/data?default") sample-ttl)))
        "data round-trip on a non-default port")
    (is (= :healthy (health cid)) "docker reports healthy — HEALTHCHECK followed the port")))

(deftest s20-edn-port-is-honoured-and-the-healthcheck-follows
  (let [edn (fixture "port.edn"
                     (str "{:server {:port " alt-port "}\n"
                          " :datasets [{:name \"ds\" :storage :mem :endpoints #{:query :gsp-rw}}]}"))]
    (with-container [cid {:mounts [(str edn ":/fuseki/fuseki.edn:ro")] :cport alt-port}]
      (wait-ping)
      (is (= 200 (status (str base "/$/ping")))
          "listening on the EDN's port with no FUSEKI_PORT set")
      (is (ask (str base "/ds/sparql") "ASK {}") "queries answered")
      (is (= :healthy (health cid))
          "docker reports healthy — HEALTHCHECK read the resolved port, not the env")
      (is (re-find (re-pattern (str "port: " alt-port " \\(from fuseki\\.edn")) (logs cid))
          "resolved value logged with its source")
      (is (exec-ok? cid "sh" "-c" (str "grep -qx '" alt-port "' \"${FUSEKI_BASE}/port\""))
          "effective port written where the healthcheck reads it"))))

(deftest s21-explicit-fuseki-port-beats-the-edn
  (let [edn (fixture "port2.edn"
                     (str "{:server {:port " alt-port "}\n"
                          " :datasets [{:name \"ds\" :storage :mem :endpoints #{:query}}]}"))]
    (with-container [cid {:env {"FUSEKI_PORT" "9090"}
                          :mounts [(str edn ":/fuseki/fuseki.edn:ro")]
                          :cport "9090"}]
      (wait-ping)
      (is (ask (str base "/ds/sparql") "ASK {}")
          (str "env won over the EDN (9090, not " alt-port ")")))))

(deftest s22-the-exec-log-is-a-pasteable-argv
  (with-container [cid {}]
    (wait-ping)
    (let [l (logs cid)]
      ;; `log` is println with varargs, so "--port=" port printed "--port= 3030".
      (is (not (re-find #"--port= |--config= " l))
          "no space after '=' — the logged argv must be pasteable")
      (is (re-find #"exec: java .*--port=3030 --config=/fuseki/run/config\.effective\.ttl" l)
          "the exec line shows the real java argv, not a stand-in name"))))

;; ---------------------------------------------------------------------------
;; 23. every remaining documented override
;; ---------------------------------------------------------------------------

(deftest s23-every-documented-override-takes-effect
  ;; Not because any is suspected — all seven were probed by hand. Because
  ;; "documented and never executed" was the state of :server :port, :ui :enabled
  ;; and :auth :password, and all three were wrong.
  (let [alt-edn  (fixture "alt.edn" "{:datasets [{:name \"alt\" :storage :mem :endpoints #{:query :gsp-rw}}]}")
        tdb2-edn (fixture "tdb2.edn" "{:datasets [{:name \"kb\" :storage :tdb2 :endpoints #{:query :gsp-rw}}]}")
        alt-ini  (fixture "alt-shiro.ini"
                          (str "[main]\nssl.enabled = false\n[users]\nzed = zpw\n"
                               "[roles]\n[urls]\n/$/ping = anon\n/** = authcBasic\n"))]
    (doseq [[label opts check]
            [["FUSEKI_DATASET renames the generated dataset"
              {:env {"FUSEKI_DATASET" "kb"}}
              #(ask (str base "/kb/sparql") "ASK {}")]
             ["FUSEKI_ADMIN_USER sets the basic-auth user"
              {:env {"FUSEKI_AUTH" "basic" "FUSEKI_ADMIN_PASSWORD" "pw" "FUSEKI_ADMIN_USER" "carol"}}
              #(ask (str base "/ds/sparql") "ASK {}" {:basic-auth ["carol" "pw"]})]
             ["FUSEKI_CONFIG reads a config.ttl from elsewhere"
              {:env {"FUSEKI_CONFIG" "/alt/config.ttl"} :mounts [(str config-ttl ":/alt/config.ttl:ro")]}
              #(ask (str base "/training/sparql") "ASK {}")]
             ["FUSEKI_EDN reads a fuseki.edn from elsewhere"
              {:env {"FUSEKI_EDN" "/alt/f.edn"} :mounts [(str alt-edn ":/alt/f.edn:ro")]}
              #(ask (str base "/alt/sparql") "ASK {}")]
             ["FUSEKI_SHIRO reads a shiro.ini from elsewhere"
              {:env {"FUSEKI_SHIRO" "/alt/s.ini"} :mounts [(str alt-ini ":/alt/s.ini:ro")]}
              #(ask (str base "/ds/sparql") "ASK {}" {:basic-auth ["zed" "zpw"]})]
             ["FUSEKI_BASE relocates the runtime dir"
              {:env {"FUSEKI_BASE" "/fuseki/alt-run"}}
              #(ask (str base "/ds/sparql") "ASK {}")]]]
      (with-container [cid opts]
        (wait-ping)
        (is (check) label)))
    ;; This one is asserted inside the container, on the rendered TTL.
    (with-container [cid {:env {"FUSEKI_TDB2_ROOT" "/fuseki/databases/alt"}
                          :mounts [(str tdb2-edn ":/fuseki/fuseki.edn:ro")]}]
      (wait-ping)
      (is (exec-ok? cid "sh" "-c"
                    "grep -q 'tdb2:location \"/fuseki/databases/alt/kb\"' /fuseki/run/config.effective.ttl")
          "FUSEKI_TDB2_ROOT moves rendered tdb2 locations"))))

;; ---------------------------------------------------------------------------
;; 24-27. endpoint routing, #include, and the configuration/ mount trap
;; ---------------------------------------------------------------------------

(deftest s24-endpoints-can-be-named-and-can-answer-at-the-root
  ;; The two live bugs found by the first external migration: :query rendered as
  ;; "sparql" with no way to ask for anything else, and no way to get an endpoint
  ;; on the dataset URL itself. Both were silent — a 404 and a 400.
  (let [edn (fixture "routes.edn"
                     (str "{:datasets [{:name \"kb\" :storage :mem"
                          " :endpoints {:query [\"sparql\" \"query\" \"\"]"
                          "             :gsp-rw true}}]}"))]
    (with-container [cid {:mounts [(str edn ":/fuseki/fuseki.edn:ro")]}]
      (wait-ping)
      (is (ask (str base "/kb/sparql") "ASK {}") "conventional name still answers")
      (is (ask (str base "/kb/query") "ASK {}") "an explicitly named endpoint answers")
      (is (ask (str base "/kb") "ASK {}") "the dataset root answers — the unnamed endpoint")
      (is (str/includes? (logs cid) "/kb/query")
          "the resolved routes are logged, so a surprising path is read not discovered"))))

(deftest s25-include-splices-config-files
  ;; #include is ours rather than a config library's: the tag set stays closed,
  ;; so every tag that works here is one this suite exercises.
  (let [_    (fixture "parts/kb.edn" "{:name \"kb\" :storage :mem :endpoints #{:query}}")
        _    (fixture "parts/alt.edn" "{:name \"alt\" :storage :mem :endpoints #{:query}}")
        top  (fixture "top.edn" "{:datasets [#include \"parts/kb.edn\" #include \"parts/alt.edn\"]}")]
    (with-container [cid {:mounts [(str top ":/conf/fuseki.edn:ro")
                                   (str tmp "/parts:/conf/parts:ro")]
                          :env {"FUSEKI_EDN" "/conf/fuseki.edn"}}]
      (wait-ping)
      (is (ask (str base "/kb/sparql") "ASK {}") "first included dataset served")
      (is (ask (str base "/alt/sparql") "ASK {}") "second included dataset served")))
  (testing "a missing include is FATAL at boot and says where it looked"
    (let [broken (fixture "broken.edn" "{:datasets [#include \"nope.edn\"]}")
          out    (boot-output {:mounts [(str broken ":/fuseki/fuseki.edn:ro")]})]
      (is (str/includes? out "FATAL") "refuses to boot rather than half-configuring")
      (is (str/includes? out "does not exist") "and says what was missing"))))

(deftest s26-mounting-into-configuration-does-not-break-the-boot
  ;; /fuseki/run/configuration is a path Fuseki's own docs send you to. Mounting a
  ;; file into it made Docker create the missing parent root-owned, and Fuseki
  ;; died "Not writable" before serving anything — the same class as the
  ;; /fuseki/databases ownership trap, on a path we hadn't pre-created.
  (let [extra (fixture "extra.ttl" (slurp config-ttl))]
    (with-container [cid {:mounts [(str extra ":/fuseki/run/configuration/extra.ttl:ro")]}]
      (wait-ping)
      (is (= 200 (status (str base "/$/ping"))) "boots with a file mounted into configuration/")
      (is (not (str/includes? (logs cid) "Not writable"))
          "the pre-created directory is owned by uid 1000"))))

(deftest s27-the-default-config-reports-its-routes
  (testing "the zero-config case is where the conventional-name surprise bites first"
    (with-container [cid {}]
      (wait-ping)
      (let [l (logs cid)]
        (is (str/includes? l "routes:") "routes are logged for the generated default")
        (is (str/includes? l "query /ds/sparql")
            "the operation is named alongside the path — a bare path half-answers")))))

(deftest s28-an-explicitly-set-config-path-that-is-missing-is-fatal
  ;; Absence of the DEFAULT path means "no config of that kind, carry on".
  ;; Absence of a path someone EXPLICITLY set is an instruction we couldn't
  ;; honour, and falling through to the generated default hands you a working
  ;; server serving something you never asked for.
  (doseq [[var val] [["FUSEKI_EDN" "/cfg/not-here.edn"]
                     ["FUSEKI_CONFIG" "/cfg/not-here.ttl"]
                     ["FUSEKI_SHIRO" "/cfg/not-here.ini"]]]
    (let [out (boot-output {:env {var val}})]
      (is (str/includes? out "FATAL") (str var " pointing nowhere refuses to boot"))
      (is (str/includes? out val) (str var "'s message names the path it was given"))))
  (testing "while the defaults being absent is still the normal, quiet case"
    (with-container [cid {}]
      (wait-ping)
      (is (= 200 (status (str base "/$/ping")))
          "no config mounted anywhere still boots the generated default"))))

;; ---------------------------------------------------------------------------

(defn -main [& _]
  (println (str "== sp-fuseki smoke: " image " =="))
  (let [{:keys [fail error test] :as summary} (run-tests 'smoke)]
    (docker "volume" "rm" "-f" volume)
    (fs/delete-tree tmp)
    (println (format "== %d sections, %d assertions, %d failures, %d errors =="
                     test (:pass summary) fail error))
    (System/exit (if (pos? (+ fail error)) 1 0))))

(when (= *file* (System/getProperty "babashka.file")) (-main))
