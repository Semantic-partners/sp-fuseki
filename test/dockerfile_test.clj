(ns dockerfile-test
  "Structural tests over image/Dockerfile.

  These are weaker than the smoke suite — they read text rather than run a
  container — and they exist for one thing the smoke suite genuinely cannot see:
  whether a download was VERIFIED. A corrupted artifact that still unpacks
  produces an image that boots and passes every behavioural assertion, and the
  only place the check can live is the build itself.

  Written because babashka was checksummed with a comment explaining why, while
  the ~200MB JRE and the Fuseki tarball beside it were not. The rule was in a
  comment; nothing enforced it. Now the third artifact can't be added without one.

  Run: bash test/unit.sh"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]))

(def dockerfile (slurp "image/Dockerfile"))

(defn- run-blocks
  "The Dockerfile's RUN instructions as single shell scripts — which is what Docker
  executes.

  Comment lines are dropped BEFORE joining continuations, because that is what the
  Dockerfile parser does: a whole-line comment inside a continued RUN is removed and
  the `\\` before it still joins to the line after. The first version of this
  function didn't, so a comment placed mid-RUN truncated the block and the test
  reported the Fuseki download as unverified. Confirmed against a real build: the
  executed script contains the full chain and prints `/tmp/fuseki.tgz: OK`."
  [text]
  (->> (str/split-lines text)
       (remove #(str/starts-with? (str/triml %) "#"))
       (str/join "\n")
       (#(str/replace % #"\\\n\s*" " "))
       (#(str/split % #"\n"))
       (filter #(str/starts-with? % "RUN "))))

(def ^:private artifact-download
  ;; Match the OUTPUT path, not the URL. Every URL here is assembled from shell
  ;; variables ($BASE, ${FILE}), so looking for ".tar.gz" in the URL text matched
  ;; nothing at all — and a test that silently matches nothing passes vacuously,
  ;; which is why the count assertion below exists.
  #"\$CURL[^;]*-o\s+(\S+\.tgz)")

(deftest every-downloaded-artifact-is-checksum-verified
  (let [blocks (filter #(re-find artifact-download %) (run-blocks dockerfile))]
    (testing "the artifacts are found at all (a rename must not silently pass)"
      (is (= 3 (count blocks))
          "expected exactly three artifact downloads: JRE, Fuseki, babashka"))
    (doseq [b blocks
            :let [out (second (re-find artifact-download b))]]
      (testing (str "verified: " out)
        (is (re-find #"sha(256|512)sum -c" b)
            (str "this RUN block downloads " out
                 " and never checks a hash — a truncated download would ship"))))))

(deftest verification-uses-the-upstream-published-hash
  (testing "each check reads a fetched sidecar, not a hash pasted into the file"
    ;; A literal hash in the Dockerfile would be verification too, but it would
    ;; have to be updated by hand on every version bump — and the bump is
    ;; Renovate's, which would leave the hash stale and the build broken.
    (doseq [[artifact sidecar] {"jre"    #"\$CURL[^;]*sha256\.txt\""
                                "fuseki" #"\$CURL[^;]*\.sha512\""
                                "bb"     #"\$CURL[^;]*\.sha256\""}]
      (is (re-find sidecar dockerfile)
          (str artifact " should fetch its checksum from upstream")))))

(deftest presence-checks-are-not-mistaken-for-integrity
  (testing "`test -f` may stay, but only alongside a real check"
    (let [block (first (filter #(str/includes? % "test -f /out/fuseki/fuseki-server.jar")
                              (run-blocks dockerfile)))]
      (is (some? block))
      (is (re-find #"sha512sum -c" block)
          "test -f passes for a truncated tarball that still yields the path"))))

(deftest the-final-stage-proves-the-binaries-run-on-the-target-arch
  ;; The fetch stage runs on the BUILD platform, so it can unpack target-arch
  ;; binaries but never execute them. This is the only place that can.
  (is (re-find #"RUN set -eux; bb --version; java -version" dockerfile)
      "the target stage must exec both binaries it copied in"))

(deftest downloads-retry-patiently
  ;; GitHub rate-limits release downloads; a fixed short delay lost to a 503.
  (let [curl (re-find #"ENV CURL=\"([^\"]+)\"" dockerfile)]
    (is (some? curl))
    (is (str/includes? (second curl) "--retry-all-errors"))
    (is (str/includes? (second curl) "--retry-max-time"))
    (testing "and no fixed --retry-delay, which defeats exponential backoff"
      (is (not (str/includes? (second curl) "--retry-delay"))))))
