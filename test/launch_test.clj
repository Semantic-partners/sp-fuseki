(ns launch-test
  "Launch decisions — main class, JVM arguments, log4j configuration, classpath.

  Every case here corresponds to a line in the dist's own `fuseki-server` script,
  because that script is what this image's entrypoint replaced. The bug these
  tests exist to prevent is not a crash: it is a variable that is still set, still
  documented upstream, and silently does nothing. Four of them were in that state
  — MAIN, JVM_ARGS, JAVA_OPTIONS and LOGGING — and nothing failed, which is why
  they survived for months.

  Run: bash test/unit.sh"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [sp-fuseki.launch :as l]))

;; ---------------------------------------------------------------------------
;; Main class
;; ---------------------------------------------------------------------------

(deftest ui-and-headless-keep-their-classes
  ;; The default is the jar's manifest Main-Class, not the script's `serverUI`
  ;; default — because `java -jar` is what this image used to do, and a fix for
  ;; the classpath must not quietly change which server runs.
  (is (= "org.apache.jena.fuseki.main.cmds.FusekiServerCmd"
         (:class (l/main-class {:ui "on"}))))
  (is (= "org.apache.jena.fuseki.main.cmds.FusekiServerPlainCmd"
         (:class (l/main-class {:ui "off"}))))
  (testing "and each says where it came from"
    (is (= "FUSEKI_UI=on" (:from (l/main-class {:ui "on"}))))))

(deftest every-name-the-dist-script-accepts-works-here
  ;; Someone migrating has MAIN set to one of these already. The mapping is the
  ;; script's `case`, so a value that worked there works here.
  (doseq [[name expected]
          {"basic"        "FusekiBasicCmd"
           "main"         "FusekiMainCmd"
           "plain"        "FusekiServerPlainCmd"
           "server-plain" "FusekiServerPlainCmd"
           "serverui"     "FusekiServerUICmd"
           "server-ui"    "FusekiServerUICmd"}]
    (is (str/ends-with? (:class (l/main-class {:main name :ui "on"})) (str "." expected))
        (str "MAIN=" name))))

(deftest main-is-case-insensitive-because-the-script-spells-it-three-ways
  ;; The script matches "serverui" | "server-ui" | "serverUI" in one branch.
  (is (= (:class (l/main-class {:main "serverUI" :ui "on"}))
         (:class (l/main-class {:main "serverui" :ui "on"}))
         (:class (l/main-class {:main "SERVER-UI" :ui "on"})))))

(deftest a-class-can-be-named-outright
  ;; The script's table is a convenience, not a fence. A FusekiModule author with
  ;; their own cmd class should not have to patch this image to launch it.
  (let [r (l/main-class {:main "com.example.MyCmd" :ui "on"})]
    (is (= "com.example.MyCmd" (:class r)))
    (is (str/includes? (:from r) "MAIN"))))

(deftest main-beats-fuseki-ui-and-says-so
  (let [r (l/main-class {:main "plain" :ui "on" :ui-explicit? true})]
    (is (str/ends-with? (:class r) "FusekiServerPlainCmd"))
    (is (= "FUSEKI_UI=on" (:ignored r)) "the loser must be named, not silently dropped"))
  (testing "but an unset FUSEKI_UI is not reported as overridden — that is noise on every boot"
    (is (nil? (:ignored (l/main-class {:main "plain" :ui "on" :ui-explicit? false}))))))

(deftest an-unknown-main-is-refused-with-the-list
  (let [{:keys [error class]} (l/main-class {:main "uii" :ui "on"})]
    (is (nil? class) "nothing launches")
    (is (str/includes? error "uii"))
    (is (str/includes? error "serverui") "and the message lists what would have worked")))

(deftest a-bad-fuseki-ui-is-still-refused
  (is (some? (:error (l/main-class {:ui "yes"})))))

;; ---------------------------------------------------------------------------
;; JVM arguments
;; ---------------------------------------------------------------------------

(deftest jvm-args-reads-both-names-in-the-wild
  ;; JVM_ARGS is the dist script's; JAVA_OPTIONS is the official container's.
  (is (= ["-Xmx8G"] (:args (l/jvm-args {:jvm-args "-Xmx8G"}))))
  (is (= ["-Xmx8G"] (:args (l/jvm-args {:java-options "-Xmx8G"}))))
  (testing "the script's name wins, since that is the script this entrypoint replaced"
    (let [r (l/jvm-args {:jvm-args "-Xmx1G" :java-options "-Xmx2G"})]
      (is (= ["-Xmx1G"] (:args r)))
      (is (= "JVM_ARGS" (:from r))))))

(deftest jvm-args-splits-on-whitespace
  ;; `JVM_ARGS="-Xmx4G -XX:+UseZGC"` is one variable and two arguments. Passing it
  ;; as a single argv entry gives the JVM "Unrecognized option".
  (is (= ["-Xmx4G" "-XX:+UseZGC"] (:args (l/jvm-args {:jvm-args "-Xmx4G -XX:+UseZGC"}))))
  (is (= ["-Xmx4G" "-Xms4G"] (:args (l/jvm-args {:jvm-args "  -Xmx4G   -Xms4G  "})))))

(deftest unset-means-no-arguments-not-upstreams-default
  ;; A deliberate deviation, and the reason is in the docstring: upstream's -Xmx4G
  ;; predates container-aware JVMs and ignores the limit the container was given.
  ;; Asserted so the deviation is a decision rather than an oversight.
  (let [r (l/jvm-args {})]
    (is (= [] (:args r)))
    (is (str/includes? (:from r) "container-aware")))
  (testing "empty is the same as unset"
    (is (= [] (:args (l/jvm-args {:jvm-args "   "}))))))

;; ---------------------------------------------------------------------------
;; Logging
;; ---------------------------------------------------------------------------

(deftest logging-is-passed-through-whole
  ;; In the script LOGGING is a complete JVM flag, not a path — someone may be
  ;; setting a different log4j property, or something else entirely.
  (is (= "-Dlog4j.configurationFile=/my/log4j2.xml"
         (:arg (l/logging-arg {:logging "-Dlog4j.configurationFile=/my/log4j2.xml"})))))

(deftest a-mounted-properties-file-beats-the-dists
  (let [r (l/logging-arg {:mounted? true :mounted-path "/fuseki/log4j2.properties"
                          :dist? true :dist-path "/opt/fuseki/log4j2.properties"})]
    (is (= "-Dlog4j.configurationFile=/fuseki/log4j2.properties" (:arg r)))
    (is (str/includes? (:from r) "mounted"))))

(deftest the-dists-file-is-the-fallback-so-logs-look-like-upstreams
  (let [r (l/logging-arg {:dist? true :dist-path "/opt/fuseki/log4j2.properties"})]
    (is (= "-Dlog4j.configurationFile=/opt/fuseki/log4j2.properties" (:arg r)))))

(deftest no-file-anywhere-means-no-flag
  ;; Not a made-up path. Pointing log4j at a file that does not exist is worse
  ;; than letting it use its built-in default.
  (is (nil? (l/logging-arg {}))))

;; ---------------------------------------------------------------------------
;; Classpath and argv
;; ---------------------------------------------------------------------------

(deftest the-classpath-uses-the-jvms-own-wildcard
  ;; `dir/*` is expanded by the launcher, not the shell, so it survives exec with
  ;; no globbing and no word-splitting.
  (is (= "/j/f.jar" (l/classpath "/j/f.jar" nil)))
  (is (= "/j/f.jar:/fuseki/run/extra/*" (l/classpath "/j/f.jar" "/fuseki/run/extra"))))

(deftest argv-is-in-the-scripts-order
  ;; java, JVM args, logging, -cp, class, then the server's own arguments. Order
  ;; is not cosmetic: a JVM flag after the class name is an argument to Fuseki.
  (is (= ["java" "-Xmx1G" "-Dlog4j.configurationFile=/l.properties"
          "-cp" "/f.jar" "some.Main" "--port=3030"]
         (l/argv {:java "java" :jvm ["-Xmx1G"] :logging "-Dlog4j.configurationFile=/l.properties"
                  :cp "/f.jar" :class "some.Main" :args ["--port=3030"]}))))

(deftest argv-omits-what-is-absent
  (is (= ["java" "-cp" "/f.jar" "some.Main"]
         (l/argv {:java "java" :jvm [] :logging nil :cp "/f.jar" :class "some.Main" :args []}))))
