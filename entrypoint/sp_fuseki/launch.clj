(ns sp-fuseki.launch
  "Pure launch decisions: which main class, which JVM arguments, which log4j
  configuration, what classpath.

  **The dist's own `fuseki-server` script is the specification here**, not our
  taste. This entrypoint replaces that script, and every knob the script honours
  is a knob someone's existing deployment may be using. Replacing a launcher and
  keeping four of its seven extension points is how a drop-in image stops being
  one — silently, because the variable is still set and simply does nothing.

  The script's shape, reduced to what it decides:

      CP=$JAR
      if [ -d \"$FUSEKI_BASE/extra\" ] ; then CP=\"${CP}:${FUSEKI_BASE}/extra/*\" ; fi
      JVM_ARGS=${JVM_ARGS:--Xmx4G}
      LOGGING=${LOGGING:--Dlog4j.configurationFile=$FUSEKI_HOME/log4j2.properties}
      MAIN=${MAIN:-serverUI}                     # a short name, mapped to a class
      exec \"$JAVA\" $JVM_ARGS \"$LOGGING\" -cp \"$CP\" \"$MAIN\" \"$@\"

  The official Apache container is a second source, and it does not agree with the
  script: it reads `JAVA_OPTIONS` where the script reads `JVM_ARGS`. Both are in
  the wild, so both are read here, and the resolved value is logged with the name
  it came from.

  Pure so the decisions can be unit-tested without Docker (see test/launch_test.clj)."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Main class — the server posture
;; ---------------------------------------------------------------------------
;;
;; Five classes ship in the jar and the script names four of them. `serverUI` is
;; the script's default; the jar's manifest Main-Class is FusekiServerCmd, which
;; is what `java -jar` used to run here. Those are different classes, so the
;; default below is the manifest one — matching what this image did before the
;; classpath moved to `-cp`, rather than what the script would have chosen.

(def dist-mains
  "The script's `case $MAIN` table, verbatim. Its keys are what someone's existing
  deployment will have in an env var."
  {"basic"        "org.apache.jena.fuseki.main.cmds.FusekiBasicCmd"
   "main"         "org.apache.jena.fuseki.main.cmds.FusekiMainCmd"
   "plain"        "org.apache.jena.fuseki.main.cmds.FusekiServerPlainCmd"
   "server-plain" "org.apache.jena.fuseki.main.cmds.FusekiServerPlainCmd"
   "serverui"     "org.apache.jena.fuseki.main.cmds.FusekiServerUICmd"
   "server-ui"    "org.apache.jena.fuseki.main.cmds.FusekiServerUICmd"})

(def ui-main
  "The jar's manifest Main-Class — what `java -jar` ran before this image put a
  classpath together. `java -jar` IGNORES `-cp`, so the moment `extra/` exists
  both postures must launch by class, and the UI one has to name this."
  "org.apache.jena.fuseki.main.cmds.FusekiServerCmd")

(def plain-main
  "The headless server, same class the dist's `fuseki-plain` script selects."
  "org.apache.jena.fuseki.main.cmds.FusekiServerPlainCmd")

(defn main-class
  "Which class to launch, and why. `{:class .. :from ..}` or `{:error ..}`.

  `MAIN` beats `FUSEKI_UI` when both are set, and the loser is named in `:ignored`
  so a boot cannot honour one knob while a reader believes the other applied —
  but only when `FUSEKI_UI` was actually set, since reporting a default as
  overridden is noise on every boot. A fully-qualified name is passed through: the
  script's table is a convenience over naming a class, not a fence around which
  classes exist."
  [{:keys [main ui ui-explicit?]}]
  (let [m (some-> main str/trim not-empty)]
    (cond
      (nil? m)
      (case ui
        "on"  {:class ui-main    :from "FUSEKI_UI=on"}
        "off" {:class plain-main :from "FUSEKI_UI=off"}
        {:error (str "ui must be 'on' or 'off', got: " ui)})

      (str/includes? m ".")
      {:class m :from "MAIN (class named directly)"
       :ignored (when ui-explicit? (str "FUSEKI_UI=" ui))}

      (contains? dist-mains (str/lower-case m))
      {:class (dist-mains (str/lower-case m)) :from (str "MAIN=" m)
       :ignored (when ui-explicit? (str "FUSEKI_UI=" ui))}

      :else
      {:error (str "MAIN=" m " is not one of " (str/join ", " (sort (keys dist-mains)))
                   " — or give a fully-qualified class name. This is the same set the"
                   " dist's fuseki-server script accepts.")})))

;; ---------------------------------------------------------------------------
;; JVM arguments
;; ---------------------------------------------------------------------------

(defn jvm-args
  "JVM arguments and where they came from. `JVM_ARGS` (the dist script) wins over
  `JAVA_OPTIONS` (the official container), because the script is what this
  entrypoint replaced.

  **We do NOT adopt upstream's default.** The script defaults to `-Xmx4G` and the
  official image to `-Xmx4096m -Xms4096m`; both predate container-aware JVMs and
  both ignore the memory limit the container was actually given. With no arguments
  a modern JVM takes 25% of the container limit and moves with it, so a 2GB
  container gets a heap that fits instead of one that gets OOM-killed. Deliberate
  deviation, logged at boot, and overridable by exactly the variables upstream
  documents."
  [{:keys [jvm-args java-options]}]
  (let [pick (fn [v n] (when-let [s (some-> v str/trim not-empty)] {:args (str/split s #"\s+") :from n}))]
    (or (pick jvm-args "JVM_ARGS")
        (pick java-options "JAVA_OPTIONS")
        {:args [] :from "unset — the JVM's container-aware default heap"})))

;; ---------------------------------------------------------------------------
;; Logging
;; ---------------------------------------------------------------------------

(defn logging-arg
  "The `-Dlog4j.configurationFile=` argument, and where the file came from.

  `LOGGING` is passed through verbatim because that is what the script does with
  it — it is a whole JVM flag there, not a path, and someone using it may be
  setting something other than log4j. Otherwise a mounted properties file wins,
  and the dist's own file is the fallback so our logs look like upstream's rather
  than like log4j's built-in default.

  `nil` means launch with no logging flag at all."
  [{:keys [logging mounted? mounted-path dist? dist-path]}]
  (cond
    (some-> logging str/trim not-empty) {:arg (str/trim logging) :from "LOGGING"}
    mounted? {:arg (str "-Dlog4j.configurationFile=" mounted-path) :from (str "mounted " mounted-path)}
    dist?    {:arg (str "-Dlog4j.configurationFile=" dist-path)    :from (str "the dist's " dist-path)}
    :else    nil))

;; ---------------------------------------------------------------------------
;; Classpath
;; ---------------------------------------------------------------------------

(defn jars-in
  "The jar files in `dir`, as a seq. Sorted, because a classpath's order decides
  which duplicate class wins and an unordered one makes that a coin toss."
  [dir]
  (sort (filter #(str/ends-with? (str %) ".jar")
                (map str (.listFiles (java.io.File. (str dir)))))))

(defn extra-dir
  "The extra jar directory if it exists and holds a jar, else nil — absent and
  empty are the same answer to \"is there a classpath to extend\"."
  [base]
  (let [d (java.io.File. (str base) "extra")]
    (when (and (.isDirectory d) (seq (jars-in d)))
      (str d))))

(defn classpath
  "`jar`, plus every jar in `extra`. `dir/*` is the JVM's own wildcard, expanded by
  the launcher rather than the shell, so it survives `exec` with no globbing."
  [jar extra]
  (if extra (str jar ":" extra "/*") jar))

(defn argv
  "The whole command line, in the dist script's order: java, JVM args, logging,
  classpath, class, then the server's own arguments."
  [{:keys [java jvm logging cp class args]}]
  (vec (concat [java] jvm (when logging [logging]) ["-cp" cp class] args)))
