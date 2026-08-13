(ns render-test
  "Unit tests for the fuseki.edn -> TTL renderer.

  THESE TESTS ARE DOCUMENTATION. Each name states a rule of the contract, and
  each assertion shows the input that triggers it. If you want to know what the
  EDN layer promises, read this file — and if you change the promise, change it
  here first.

  Run: bash test/unit.sh   (no Docker needed)"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [sp-fuseki.render :as r]))

(def minimal
  {:datasets [{:name "ds" :storage :mem :endpoints #{:query :update :gsp-rw}}]})

(defn- msg
  "The message a bad config fails with — we assert on these because a confusing
  error is the bug this project exists to avoid."
  [cfg]
  (try (r/edn->ttl cfg) nil
       (catch Exception e (ex-message e))))

(defn- has?
  "Substring check ignoring run-length of whitespace. The renderer pads columns
  so the effective config reads nicely; these tests assert the CONTRACT, not the
  cosmetics, so re-aligning output must not fail them."
  [haystack needle]
  (let [norm #(str/trim (str/replace % #"\s+" " "))]
    (str/includes? (norm haystack) (norm needle))))

;; ---------------------------------------------------------------------------
;; What the EDN buys you over hand-written TTL
;; ---------------------------------------------------------------------------

(deftest a-minimal-config-renders-a-usable-service
  (let [ttl (r/edn->ttl minimal)]
    (is (str/includes? ttl "fuseki:name \"ds\""))
    (is (str/includes? ttl "a ja:RDFDataset"))
    (is (str/includes? ttl "ja:defaultGraph [ a ja:MemoryModel ]"))
    (testing "every requested endpoint appears with its conventional path"
      (is (has? ttl "fuseki:operation fuseki:query ; fuseki:name \"sparql\""))
      (is (has? ttl "fuseki:operation fuseki:update ; fuseki:name \"update\""))
      (is (has? ttl "fuseki:operation fuseki:gsp-rw ; fuseki:name \"data\"")))
    (testing "the generated file says it's generated, and where to go instead"
      (is (str/includes? ttl "GENERATED"))
      (is (str/includes? ttl "config.ttl")))))

(deftest prefixes-are-declared-for-you
  (let [ttl (r/edn->ttl (assoc minimal :prefixes {:ex "http://example.org/"
                                                  :geo "http://geo.org/"}))]
    (testing "user prefixes — the ergonomics TTL makes you repeat by hand"
      (is (str/includes? ttl "@prefix ex:     <http://example.org/> ."))
      (is (str/includes? ttl "@prefix geo:    <http://geo.org/> .")))
    (testing "assembler prefixes are always present so the output stands alone"
      (is (str/includes? ttl "@prefix fuseki:"))
      (is (str/includes? ttl "@prefix ja:"))
      (is (str/includes? ttl "@prefix tdb2:")))))

(deftest multiple-datasets-each-get-their-own-service
  (let [ttl (r/edn->ttl {:datasets [{:name "a" :storage :mem :endpoints #{:query}}
                                    {:name "b" :storage :mem :endpoints #{:query}}]})]
    (is (= 2 (count (re-seq #"a fuseki:Service" ttl))))
    (is (str/includes? ttl "fuseki:name \"a\""))
    (is (str/includes? ttl "fuseki:name \"b\""))))

(deftest rendering-is-deterministic
  (testing "same EDN renders byte-identical TTL, so the effective config diffs cleanly"
    (let [cfg (assoc minimal :prefixes {:z "http://z/" :a "http://a/"})]
      (is (= (r/edn->ttl cfg) (r/edn->ttl cfg)))
      (testing "and set-order of :endpoints cannot change the output"
        (is (= (r/edn->ttl {:datasets [{:name "d" :storage :mem :endpoints #{:query :update}}]})
               (r/edn->ttl {:datasets [{:name "d" :storage :mem :endpoints #{:update :query}}]})))))))

;; ---------------------------------------------------------------------------
;; TDB2 — the trap the mount docs exist for
;; ---------------------------------------------------------------------------

(deftest tdb2-locations-land-under-the-writable-mount
  (let [ttl (r/edn->ttl {:datasets [{:name "kb" :storage :tdb2 :endpoints #{:query}}]})]
    (testing "absolute, under /fuseki/databases — the dir the image pre-owns as uid 1000"
      (is (str/includes? ttl "a tdb2:DatasetTDB2"))
      (is (str/includes? ttl "tdb2:location \"/fuseki/databases/kb\""))))
  (testing "the root is overridable for anyone mounting elsewhere"
    (is (str/includes? (r/edn->ttl {:datasets [{:name "kb" :storage :tdb2 :endpoints #{:query}}]}
                                   {:tdb2-root "/data"})
                       "tdb2:location \"/data/kb\""))))

(deftest reasoner-renders-an-infmodel-over-the-base-graph
  (let [ttl (r/edn->ttl {:datasets [{:name "inf" :storage :mem :endpoints #{:query}
                                     :reasoner :rdfs}]})]
    (is (str/includes? ttl "a ja:InfModel"))
    (is (str/includes? ttl "ja:baseModel [ a ja:MemoryModel ]"))
    (is (str/includes? ttl "RDFSExptRuleReasoner")))
  (testing ":none means no InfModel wrapper at all"
    (is (not (str/includes? (r/edn->ttl {:datasets [{:name "d" :storage :mem
                                                     :endpoints #{:query} :reasoner :none}]})
                            "InfModel")))))

;; ---------------------------------------------------------------------------
;; Loud failure. A config that lies is worse than one that won't boot.
;; ---------------------------------------------------------------------------

(deftest unknown-keys-are-rejected-not-ignored
  (is (str/includes? (msg (assoc minimal :prot 1)) "unknown top-level key"))
  (testing "and the message lists what IS accepted"
    (is (str/includes? (msg (assoc minimal :prot 1)) ":datasets"))))

(deftest federation-is-named-as-unimplemented-rather-than-dropped
  (let [m (msg (assoc minimal :federation [{:name "dbpedia" :url "http://x/"}]))]
    (is (str/includes? m "NOT implemented"))
    (is (str/includes? m "issue #2"))
    (testing "silently dropping it would produce a config that lies about federation"
      (is (str/includes? m ":federation")))))

(deftest datasets-are-required
  (is (str/includes? (msg {}) ":datasets must be a non-empty vector"))
  (is (str/includes? (msg {:datasets []}) ":datasets must be a non-empty vector")))

(deftest dataset-names-that-would-break-a-url-are-rejected
  (testing "names become URL path segments"
    (is (str/includes? (msg {:datasets [{:name "a/b" :storage :mem :endpoints #{:query}}]})
                       "URL path segment"))
    (is (str/includes? (msg {:datasets [{:name "a b" :storage :mem :endpoints #{:query}}]})
                       "URL path segment"))
    (is (str/includes? (msg {:datasets [{:name "" :storage :mem :endpoints #{:query}}]})
                       "must not be blank"))))

(deftest storage-and-endpoints-must-be-known
  (is (str/includes? (msg {:datasets [{:name "d" :storage :sqlite :endpoints #{:query}}]})
                     ":storage must be one of"))
  (is (str/includes? (msg {:datasets [{:name "d" :storage :mem :endpoints #{:qeury}}]})
                     "unknown :endpoints"))
  (testing "a dataset with no endpoints is unreachable, so it's an error"
    (is (str/includes? (msg {:datasets [{:name "d" :storage :mem :endpoints #{}}]})
                       "needs at least one of :endpoints"))))

(deftest reasoner-on-tdb2-is-refused-explicitly
  (testing "rather than emitting TTL whose behaviour we haven't decided"
    (is (str/includes? (msg {:datasets [{:name "d" :storage :tdb2 :endpoints #{:query}
                                         :reasoner :rdfs}]})
                       "not supported"))))

(deftest auth-mode-is-checked
  (is (str/includes? (msg (assoc minimal :auth {:mode :ldap})) ":auth :mode must be"))
  (is (nil? (msg (assoc minimal :auth {:mode :basic})))))

(deftest auth-accepts-credentials-and-rejects-unknown-keys
  (testing ":user and :password are honoured by the entrypoint, so they validate"
    (is (nil? (msg (assoc minimal :auth {:mode :basic :user "carol" :password "s3cret"})))))
  (testing "an unknown key is refused — silent acceptance is how :password came to be"
    (testing "documented in examples/fuseki.edn and ignored for a day"
      (let [m (msg (assoc minimal :auth {:mode :basic :passwrod "typo"}))]
        (is (str/includes? m ":auth has unknown key"))
        (is (str/includes? m ":password") "the message should list what IS accepted"))))
  (testing "a non-string credential points at the reader tags"
    (is (str/includes? (msg (assoc minimal :auth {:mode :basic :password 12345}))
                       "#env")))
  (testing "a password with :anon would do nothing, so it's an error not a no-op"
    (is (str/includes? (msg (assoc minimal :auth {:mode :anon :password "x"}))
                       "silently do nothing"))))

(deftest port-must-be-a-real-port
  (is (str/includes? (msg (assoc minimal :server {:port 0})) "1-65535"))
  (is (str/includes? (msg (assoc minimal :server {:port "3030"})) "1-65535")))

;; ---------------------------------------------------------------------------
;; Reader tags — how a secret reaches the config without being in the config
;; ---------------------------------------------------------------------------

(deftest env-tag-reads-the-environment
  (let [var (first (filter #(System/getenv %) ["HOME" "PATH" "USER"]))]
    (is (= (System/getenv var)
           (r/parse (str "#env \"" var "\""))))))

(deftest env-tag-fails-loudly-when-unset
  (let [m (try (r/parse "#env \"SP_FUSEKI_DEFINITELY_NOT_SET\"") nil
               (catch Exception e (ex-message e)))]
    (is (str/includes? m "is not set in the environment"))
    (testing "naming the variable, so the fix is obvious"
      (is (str/includes? m "SP_FUSEKI_DEFINITELY_NOT_SET")))))

(deftest file-tag-reads-and-trims-a-secret-file
  (let [f (java.io.File/createTempFile "sp-fuseki" ".secret")]
    (spit f "hunter2\n")
    (is (= "hunter2" (r/parse (str "#file \"" (.getAbsolutePath f) "\""))))
    (.delete f))
  (testing "a missing file is an error, not an empty password"
    (is (str/includes? (try (r/parse "#file \"/no/such/secret\"") nil
                            (catch Exception e (ex-message e)))
                       "does not exist"))))

(deftest malformed-edn-says-so
  (is (str/includes? (try (r/parse "{:datasets [") nil
                          (catch Exception e (ex-message e)))
                     "not valid EDN")))

;; ---------------------------------------------------------------------------
;; shiro — shared by the env and EDN paths so the fencing can't drift
;; ---------------------------------------------------------------------------

(deftest anon-shiro-fences-the-mutating-admin-api
  (testing "this is the rule that stopped anonymous dataset deletion"
    (is (str/includes? r/shiro-anon "/$/** = authcBasic"))
    (is (str/includes? r/shiro-anon "/** = anon")))
  (testing "read-only admin stays open so the UI can report"
    (doseq [open ["/$/ping = anon" "/$/server = anon" "/$/metrics = anon"]]
      (is (str/includes? r/shiro-anon open))))
  (testing "specific rules must precede the /$/** catch-all — Shiro is first-match"
    (is (< (str/index-of r/shiro-anon "/$/server = anon")
           (str/index-of r/shiro-anon "/$/** = authcBasic")))))

(deftest basic-shiro-keeps-ping-open-for-the-healthcheck
  (let [ini (r/shiro-basic "admin" "s3cret")]
    (testing "gating /$/ping makes every basic-auth container report unhealthy"
      (is (str/includes? ini "/$/ping = anon"))
      (is (< (str/index-of ini "/$/ping = anon") (str/index-of ini "/** = authcBasic"))))
    (is (str/includes? ini "admin = s3cret"))))

(deftest basic-shiro-refuses-to-render-without-a-credential
  (is (thrown? Exception (r/shiro-basic "admin" "")))
  (is (thrown? Exception (r/shiro-basic "" "pw"))))
