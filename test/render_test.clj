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

(defn- routes-of
  "r/routes with the lazy seqs realised, so failures print readably."
  [cfg]
  (mapv (fn [[n ops]] [n (mapv (fn [[op ps]] [op (vec ps)]) ops)]) (r/routes cfg)))

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

;; ---------------------------------------------------------------------------
;; Endpoints: what path a dataset actually answers on
;; ---------------------------------------------------------------------------

(deftest the-defaults-are-fusekis-defaults
  (testing "one endpoint per operation, at the name stock Fuseki uses — so a
  config written here and one written by hand put the same dataset at the same
  URL. Serving /ds/query as well was considered and rejected: it fixes a real
  papercut by shipping an alias Fuseki doesn't have, and this is a notation FOR
  the assembler rather than an improved Fuseki"
    (let [ttl (r/edn->ttl {:datasets [{:name "ds" :storage :mem :endpoints #{:query}}]})]
      (is (has? ttl "fuseki:operation fuseki:query ; fuseki:name \"sparql\""))
      (is (not (str/includes? ttl "fuseki:name \"query\"")))
      (is (= 1 (count (re-seq #"fuseki:endpoint" ttl))))))
  (testing "the papercut is answered by naming paths explicitly, not by a nicer
  default — this is the override that makes the default affordable"
    (let [ttl (r/edn->ttl {:datasets [{:name "ds" :storage :mem
                                       :endpoints {:query ["sparql" "query"]}}]})]
      (is (has? ttl "fuseki:operation fuseki:query ; fuseki:name \"sparql\""))
      (is (has? ttl "fuseki:operation fuseki:query ; fuseki:name \"query\""))))
  (testing "and the dataset root stays opt-in"
    (let [ttl (r/edn->ttl {:datasets [{:name "ds" :storage :mem :endpoints #{:query}}]})]
      (is (not (has? ttl "fuseki:endpoint [ fuseki:operation fuseki:query ] ;"))))))

(deftest endpoints-can-be-named-explicitly
  (testing "which is the answer to the surprise above: ask for /ds/query and get it"
    (let [ttl (r/edn->ttl {:datasets [{:name "ds" :storage :mem
                                       :endpoints {:query "query"}}]})]
      (is (has? ttl "fuseki:operation fuseki:query ; fuseki:name \"query\""))))
  (testing "true means 'the conventional name', so one key can be left alone"
    (let [ttl (r/edn->ttl {:datasets [{:name "ds" :storage :mem
                                       :endpoints {:query "query" :update true}}]})]
      (is (has? ttl "fuseki:operation fuseki:update ; fuseki:name \"update\""))))
  (testing "a vector gives one operation several paths — what the ES plugin's own
  config does with fuseki:serviceQuery \"sparql\", \"query\", \"\""
    (let [ttl (r/edn->ttl {:datasets [{:name "ds" :storage :mem
                                       :endpoints {:query ["sparql" "query"]}}]})]
      (is (has? ttl "fuseki:operation fuseki:query ; fuseki:name \"sparql\""))
      (is (has? ttl "fuseki:operation fuseki:query ; fuseki:name \"query\"")))))

(deftest an-endpoint-can-answer-at-the-dataset-root
  (testing "nil or \"\" means /ds itself — an endpoint with no fuseki:name.
  Without this an EDN dataset answered on /ds/sparql but 400ed on /ds, which
  breaks every client that targets the dataset URL directly"
    (doseq [spec [nil ""]]
      (let [ttl (r/edn->ttl {:datasets [{:name "kb" :storage :mem
                                         :endpoints {:query spec}}]})]
        (is (has? ttl "fuseki:endpoint [ fuseki:operation fuseki:query ] ;")
            (str "spec " (pr-str spec) " should render an unnamed endpoint"))
        (is (not (str/includes? ttl "fuseki:name \"\""))))))
  (testing "root and named endpoints coexist on one operation"
    (let [ttl (r/edn->ttl {:datasets [{:name "kb" :storage :mem
                                       :endpoints {:query ["sparql" ""]}}]})]
      (is (has? ttl "fuseki:operation fuseki:query ; fuseki:name \"sparql\""))
      (is (has? ttl "fuseki:endpoint [ fuseki:operation fuseki:query ] ;"))))
  (testing "two DIFFERENT operations at the root is legal — Fuseki dispatches on
  the request, which is exactly what a bare serviceQuery relies on"
    (is (nil? (msg {:datasets [{:name "kb" :storage :mem
                                :endpoints {:query nil :gsp-rw nil}}]})))))

(deftest endpoint-names-can-be-keywords-or-strings
  (testing "keywords match how the rest of the format spells a known value —
  :tdb2, :rdfs, :anon — and render to the same string literal"
    (is (= (r/edn->ttl {:datasets [{:name "kb" :storage :mem :endpoints {:query :foo}}]})
           (r/edn->ttl {:datasets [{:name "kb" :storage :mem :endpoints {:query "foo"}}]}))))
  (testing "mixed, because a keyword can't spell every legal path segment"
    (is (= [["kb" [[:query ["/kb/sparql" "/kb/q-2" "/kb"]]]]]
           (routes-of {:datasets [{:name "kb" :storage :mem
                                   :endpoints {:query [:sparql "q-2" nil]}}]})))))

(deftest endpoint-names-that-would-break-a-url-are-rejected
  (testing "same rule as dataset names — it becomes a path segment"
    (doseq [n ["a/b" "a b" "a?b" "a#b"]]
      (is (str/includes? (msg {:datasets [{:name "d" :storage :mem :endpoints {:query n}}]})
                         "URL path segment")
          (str "should reject " (pr-str n))))))

(deftest endpoint-specs-that-cannot-mean-anything-are-refused
  (is (str/includes? (msg {:datasets [{:name "d" :storage :mem :endpoints {:query 42}}]})
                     "must be true"))
  (testing "a set has no order, and the order endpoints are written in is kept"
    (is (str/includes? (msg {:datasets [{:name "d" :storage :mem
                                         :endpoints {:query #{"a" "b"}}}]})
                       "vector")))
  (testing "unknown operations are caught in the map form too, not just the set form"
    (is (str/includes? (msg {:datasets [{:name "d" :storage :mem :endpoints {:qeury "x"}}]})
                       "unknown :endpoints"))))

(deftest ambiguous-and-redundant-routes-are-refused
  (testing "one path cannot mean two operations"
    (is (str/includes? (msg {:datasets [{:name "d" :storage :mem
                                         :endpoints {:query "x" :update "x"}}]})
                       "more than one operation")))
  (testing "and asking for the same endpoint twice means the config says
  something it doesn't mean"
    (is (str/includes? (msg {:datasets [{:name "d" :storage :mem
                                         :endpoints {:query ["x" "x"]}}]})
                       "same endpoint more than once"))
    (is (str/includes? (msg {:datasets [{:name "d" :storage :mem
                                         :endpoints {:query [nil ""]}}]})
                       "the dataset root"))))

(deftest routes-are-computed-for-the-boot-log
  (testing "a route is a resolved decision, and an unlogged one is a 404 you have
  to go and discover"
    (is (= [["kb" [[:query ["/kb/sparql" "/kb/query" "/kb"]]]]]
           (routes-of {:datasets [{:name "kb" :storage :mem
                                   :endpoints {:query ["sparql" "query" ""]}}]}))))
  (testing "grouped BY OPERATION, because a flat path list half-answers the only
  question the line exists for — /x serving query, update and gsp-rw all at the
  root reads as a bare \"/x\" and tells a debugger nothing"
    (is (= [["x" [[:gsp-rw ["/x"]] [:query ["/x"]] [:update ["/x"]]]]]
           (routes-of {:datasets [{:name "x" :storage :mem
                                   :endpoints {:query nil :update nil :gsp-rw nil}}]}))))
  (testing "the default config's routes are reported too — that's where the
  conventional-name surprise bites first"
    (is (= [["ds" [[:gsp-rw ["/ds/data"]] [:query ["/ds/sparql"]] [:update ["/ds/update"]]]]]
           (routes-of {:datasets [{:name "ds" :storage :mem
                                   :endpoints #{:query :update :gsp-rw}}]})))))

(deftest error-messages-truncate-the-value-they-echo
  (testing "#include is unconfined by design, so a mistyped path can pull in a
  file the author never meant to read — and unlike the file, the LOG travels.
  Naming what's wrong doesn't require reproducing all of it"
    (let [secret (apply str (repeat 40 "root:x:0:0:/bin/bash "))
          m      (msg {:datasets [secret]})]
      (is (str/includes? m "truncated"))
      (is (< (count m) 250) "the message stays a message, not a file dump")
      (is (not (str/includes? m (subs secret 200))) "the tail is not echoed")))
  (testing "short values are untouched — this must not make ordinary errors worse"
    (is (= "dataset \"d\" :storage must be one of :mem, :tdb2, got: :sqlite"
           (msg {:datasets [{:name "d" :storage :sqlite :endpoints #{:query}}]})))))

;; ---------------------------------------------------------------------------
;; :text — a Lucene index, and the one place a key denotes a MODULE's vocabulary
;; ---------------------------------------------------------------------------

(def text-cfg
  {:prefixes {:skos "http://www.w3.org/2004/02/skos/core#"
              :rdfs "http://www.w3.org/2000/01/rdf-schema#"}
   :datasets [{:name "kb" :storage :tdb2 :endpoints #{:query}
               :text {:default-field :label
                      :store-values true
                      :fields {:label :rdfs/label :prefLabel :skos/prefLabel}}}]})

(deftest text-wraps-the-store-rather-than-replacing-it
  (testing "a text index is a WRAPPER — the real dataset survives inside it, which
  is why :text is a shape change to a dataset rather than another key on one"
    (let [ttl (r/edn->ttl text-cfg)]
      (is (has? ttl "fuseki:dataset [ a text:TextDataset ;"))
      (is (has? ttl "text:dataset [ a tdb2:DatasetTDB2 ;"))
      (is (str/includes? ttl "tdb2:location \"/fuseki/databases/kb\""))
      (is (has? ttl "text:index   <#kb-textindex>")))))

(deftest the-index-and-entity-map-are-NAMED-not-blank-nodes
  (testing "not cosmetic. jena-text's EntityDefinitionAssembler reads the entity
  map via ParameterizedSparqlString.setIri, so a blank node arrives as a null IRI
  and the whole config dies with a NullPointerException naming nothing the user
  wrote. Verified against a real container before this was written"
    (let [ttl (r/edn->ttl text-cfg)]
      (is (str/includes? ttl "<#kb-textindex> a text:TextIndexLucene ;"))
      (is (str/includes? ttl "<#kb-entitymap> a text:EntityMap ;"))
      (is (str/includes? ttl "text:entityMap <#kb-entitymap>")))))

(deftest the-entity-map-is-an-rdf-list-of-field-predicate-pairs
  (testing "the block whose TTL is genuinely miserable by hand — this is what
  :text buys over the escape hatch"
    (let [ttl (r/edn->ttl text-cfg)]
      (is (has? ttl "text:field \"label\" ; text:predicate rdfs:label"))
      (is (has? ttl "text:field \"prefLabel\" ; text:predicate skos:prefLabel"))
      (is (str/includes? ttl "text:map ("))))
  (testing "entityField is emitted as a constant — jena-text requires it, refuses
  the config outright without it, and its value is only observable to something
  reading the Lucene index directly. Not a key"
    (is (str/includes? (r/edn->ttl text-cfg) "text:entityField  \"uri\"")))
  (testing "a full IRI string works where no prefix is declared"
    (is (has? (r/edn->ttl {:datasets [{:name "d" :storage :mem :endpoints #{:query}
                                       :text {:fields {:label "http://x/label"}}}]})
              "text:predicate <http://x/label>"))))

(deftest text-defaults-are-emitted-for-you
  (testing "the index directory lands under the writable mount, same reasoning as
  tdb2:location — getting it wrong is the permissions trap, not a config error"
    (is (str/includes? (r/edn->ttl text-cfg) "text:directory <file:/fuseki/databases/kb-lucene>")))
  (testing "an explicit directory wins, and \"mem\" is jena-text's in-memory index
  spelled as a bare literal rather than a file: IRI"
    (is (str/includes? (r/edn->ttl (assoc-in text-cfg [:datasets 0 :text :directory] "mem"))
                       "text:directory \"mem\"")))
  (testing "analyzer defaults to standard"
    (is (has? (r/edn->ttl text-cfg) "text:analyzer  [ a text:StandardAnalyzer ]")))
  (testing "the text: prefix appears only when something uses it — an unused
  prefix in a file people are told to go and read is noise"
    (is (str/includes? (r/edn->ttl text-cfg) "@prefix text:"))
    (is (not (str/includes? (r/edn->ttl minimal) "@prefix text:")))))

(deftest text-is-validated-before-jena-sees-it
  (testing "a prefix used but not declared renders TTL Jena refuses to parse, with
  a message about the TTL rather than about the EDN anyone wrote"
    (is (str/includes? (msg {:datasets [{:name "d" :storage :mem :endpoints #{:query}
                                         :text {:fields {:label :rdfs/label}}}]})
                       "not in :prefixes")))
  (testing "an index over no fields would build cleanly and never match"
    (is (str/includes? (msg {:datasets [{:name "d" :storage :mem :endpoints #{:query}
                                         :text {:fields {}}}]})
                       "needs :fields")))
  (testing ":default-field must be one of :fields, or the default matches nothing"
    (is (str/includes? (msg (assoc-in text-cfg [:datasets 0 :text :default-field] :nope))
                       "is not one of :fields")))
  (testing "parameterised analyzers are refused by name rather than half-supported"
    (is (str/includes? (msg (assoc-in text-cfg [:datasets 0 :text :analyzer] :localized))
                       ":analyzer must be one of")))
  (testing "unknown keys in our own namespace are errors, not an escape hatch"
    (is (str/includes? (msg (assoc-in text-cfg [:datasets 0 :text :storeValues] true))
                       "unknown key")))
  (testing ":text with a reasoner is refused rather than guessed — whether the
  index should see entailed triples is a decision we haven't made"
    (is (str/includes? (msg (-> text-cfg
                                (assoc-in [:datasets 0 :storage] :mem)
                                (assoc-in [:datasets 0 :reasoner] :rdfs)))
                       "not supported"))))

(deftest rendering-is-deterministic
  (testing "same EDN renders byte-identical TTL, so the effective config diffs cleanly"
    (let [cfg (assoc minimal :prefixes {:z "http://z/" :a "http://a/"})]
      (is (= (r/edn->ttl cfg) (r/edn->ttl cfg)))
      (testing "and set-order of :endpoints cannot change the output"
        (is (= (r/edn->ttl {:datasets [{:name "d" :storage :mem :endpoints #{:query :update}}]})
               (r/edn->ttl {:datasets [{:name "d" :storage :mem :endpoints #{:update :query}}]}))))
      (testing "nor key-order of the map form"
        (is (= (r/edn->ttl {:datasets [{:name "d" :storage :mem
                                        :endpoints {:query "a" :update "b"}}]})
               (r/edn->ttl {:datasets [{:name "d" :storage :mem
                                        :endpoints {:update "b" :query "a"}}]}))))
      (testing "while the order WITHIN one operation is the author's, and kept —
      a vector has an order and the effective config should read as written"
        (is (= ["/d/x" "/d/y"]
               (-> (routes-of {:datasets [{:name "d" :storage :mem
                                           :endpoints {:query ["x" "y"]}}]})
                   first second first second)))
        (is (= ["/d/y" "/d/x"]
               (-> (routes-of {:datasets [{:name "d" :storage :mem
                                           :endpoints {:query ["y" "x"]}}]})
                   first second first second)))))))

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

(deftest two-datasets-cannot-share-a-name
  (testing "the endpoint-ambiguity rule one level up — Jena does catch this, but
  only after we have printed routes for both, and its message names neither the
  file nor which one to change"
    (let [m (msg {:datasets [{:name "x" :storage :mem :endpoints {:query "query"}}
                             {:name "x" :storage :mem :endpoints {:update "update"}}]})]
      (is (str/includes? m "share the name"))
      (is (str/includes? m "\"x\""))
      (testing "and points at the likely cause, since #include is what makes it
      reachable rather than theoretical"
        (is (str/includes? m "#include")))))
  (testing "distinct names are of course fine"
    (is (nil? (msg {:datasets [{:name "a" :storage :mem :endpoints #{:query}}
                               {:name "b" :storage :mem :endpoints #{:query}}]})))))

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
                       "needs at least one of :endpoints"))
    (testing "in either spelling, and when the key is absent entirely"
      (is (str/includes? (msg {:datasets [{:name "d" :storage :mem :endpoints {}}]})
                         "needs at least one of :endpoints"))
      (is (str/includes? (msg {:datasets [{:name "d" :storage :mem}]})
                         "needs at least one of :endpoints")))))

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

;; ---------------------------------------------------------------------------
;; #include — the tag that keeps a many-dataset config readable
;; ---------------------------------------------------------------------------

(defn- with-config-dir
  "Write {relative-path -> content} into a fresh directory and hand back its path.
  #include resolves against the including FILE, so these tests need real files in
  real directories — the resolution rule is the thing under test."
  [files]
  (let [dir (java.io.File/createTempFile "sp-fuseki-inc" "")]
    (.delete dir)
    (.mkdirs dir)
    (doseq [[path content] files
            :let [f (java.io.File. dir ^String path)]]
      (.mkdirs (.getParentFile f))
      (spit f content))
    (.getAbsolutePath dir)))

(defn- parse-file [dir path]
  (let [f (str dir "/" path)]
    (r/parse (slurp f) f)))

(defn- parse-err [dir path]
  (try (parse-file dir path) nil
       (catch Exception e (ex-message e))))

(deftest include-splices-a-file-where-the-tag-sits
  (let [dir (with-config-dir
              {"fuseki.edn"        "{:datasets [#include \"parts/kb.edn\"]}"
               "parts/kb.edn"      "{:name \"kb\" :storage :mem :endpoints #{:query}}"})]
    (testing "the included value lands exactly where the tag was written"
      (is (= {:datasets [{:name "kb" :storage :mem :endpoints #{:query}}]}
             (parse-file dir "fuseki.edn"))))
    (testing "and the result renders like any other config"
      (is (str/includes? (r/edn->ttl (parse-file dir "fuseki.edn")) "fuseki:name \"kb\"")))))

(deftest include-resolves-relative-to-the-including-file-not-the-cwd
  (testing "so a config directory works wherever it happens to be mounted"
    (let [dir (with-config-dir
                {"conf/fuseki.edn"   "{:datasets [#include \"sets/kb.edn\"]}"
                 "conf/sets/kb.edn"  "{:name \"kb\" :storage :mem :endpoints #{:query}}"})]
      (is (= "kb" (-> (parse-file dir "conf/fuseki.edn") :datasets first :name))))))

(deftest include-nests
  (let [dir (with-config-dir
              {"fuseki.edn"   "{:datasets [#include \"a.edn\"]}"
               "a.edn"        "#include \"b.edn\""
               "b.edn"        "{:name \"deep\" :storage :mem :endpoints #{:query}}"})]
    (is (= "deep" (-> (parse-file dir "fuseki.edn") :datasets first :name)))))

(defn- include-chain
  "n files, each including the next, the last holding a dataset. The point of the
  depth cap is the ACYCLIC runaway — a cycle is caught exactly, by identity, and
  never reaches it."
  [n]
  (with-config-dir
    (into {"fuseki.edn" "{:datasets [#include \"f0.edn\"]}"}
          (for [i (range n)]
            [(str "f" i ".edn")
             (if (= i (dec n))
               "{:name \"deep\" :storage :mem :endpoints #{:query}}"
               (str "#include \"f" (inc i) ".edn\""))]))))

(deftest include-nesting-is-capped
  (testing "a reasonable chain still works — the cap must not be in the way"
    (is (= "deep" (-> (parse-file (include-chain 8) "fuseki.edn") :datasets first :name))))
  (testing "a runaway reports as too deep rather than as a StackOverflowError,
  which is the whole reason the cap exists alongside cycle detection"
    (let [m (parse-err (include-chain 14) "fuseki.edn")]
      (is (str/includes? m "nested more than"))
      (is (str/includes? m "almost certainly a mistake"))
      (is (not (str/includes? (str m) "StackOverflow"))))))

(deftest include-resolution-is-relative-at-every-level
  (testing "not just the top one — an included file in a subdirectory resolves
  ITS includes against its own directory, which is what makes a config tree
  movable rather than only a flat directory"
    (let [dir (with-config-dir
                {"fuseki.edn"          "{:datasets [#include \"a/one.edn\"]}"
                 "a/one.edn"           "#include \"b/two.edn\""
                 "a/b/two.edn"         "{:name \"nested\" :storage :mem :endpoints #{:query}}"})]
      (is (= "nested" (-> (parse-file dir "fuseki.edn") :datasets first :name))))))

(deftest include-cycles-are-caught-and-name-the-trail
  (testing "a cycle must report as a cycle, not as a StackOverflowError"
    (let [dir (with-config-dir {"a.edn" "{:datasets [#include \"b.edn\"]}"
                                "b.edn" "#include \"a.edn\""})
          m   (parse-err dir "a.edn")]
      (is (str/includes? m "#include cycle"))
      (testing "and the trail says which files, in order"
        (is (str/includes? m "a.edn"))
        (is (str/includes? m "b.edn"))
        (is (str/includes? m "->")))))
  (testing "including yourself is the degenerate case and still a cycle"
    (let [dir (with-config-dir {"a.edn" "#include \"a.edn\""})]
      (is (str/includes? (parse-err dir "a.edn") "#include cycle")))))

(deftest include-of-a-missing-file-says-where-it-looked
  (testing "'does not exist' without the resolved path sends you to the wrong dir"
    (let [dir (with-config-dir {"fuseki.edn" "{:datasets [#include \"nope.edn\"]}"})
          m   (parse-err dir "fuseki.edn")]
      (is (str/includes? m "does not exist"))
      (is (str/includes? m "nope.edn"))
      (is (str/includes? m dir)))))

(deftest include-works-anywhere-a-value-can-appear
  (testing "not just in :datasets. A reader tag substitutes a VALUE, so it works
  at any position by construction rather than by permission — asserting it so
  that stays a stated property instead of an incidental one someone finds and
  depends on"
    (let [dir (with-config-dir
                {"fuseki.edn" "{:datasets [{:name \"n\" :storage :mem
                                            :endpoints #include \"eps.edn\"}]
                                :prefixes #include \"prefixes.edn\"}"
                 "eps.edn"    "{:query [\"query\" \"\"]}"
                 "prefixes.edn" "{:ex \"http://example.org/\"}"})
          cfg (parse-file dir "fuseki.edn")]
      (is (= [["n" [[:query ["/n/query" "/n"]]]]] (routes-of cfg)))
      (is (str/includes? (r/edn->ttl cfg) "@prefix ex:")))))

(deftest tags-compose-inside-an-included-file
  (testing "the tags are one set, not a top-level set and a lesser nested one —
  putting the credential in its own included file is the obvious thing to do"
    (let [dir (with-config-dir
                {"fuseki.edn" "{:datasets [{:name \"d\" :storage :mem :endpoints #{:query}}]
                                :auth #include \"auth.edn\"}"
                 "auth.edn"   "{:mode :basic :user \"carol\" :password #env \"SP_TEST_PW\"}"})]
      (if (System/getenv "SP_TEST_PW")
        (is (= "carol" (-> (parse-file dir "fuseki.edn") :auth :user)))
        (testing "and an unset #env is still loud from inside an include"
          (is (str/includes? (parse-err dir "fuseki.edn") "is not set in the environment")))))))

(deftest include-takes-a-string
  (let [dir (with-config-dir {"fuseki.edn" "{:datasets [#include :kb]}"})]
    (is (str/includes? (parse-err dir "fuseki.edn") "#include takes a string path"))))

(deftest include-accepts-an-absolute-path
  (testing "deliberately unconfined — whoever writes fuseki.edn already controls
  the whole config, so a sandbox would break shared includes for no gain"
    (let [dir (with-config-dir {"parts/kb.edn" "{:name \"kb\" :storage :mem :endpoints #{:query}}"})
          top (with-config-dir {"fuseki.edn" (str "{:datasets [#include \"" dir "/parts/kb.edn\"]}")})]
      (is (= "kb" (-> (parse-file top "fuseki.edn") :datasets first :name))))))

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
