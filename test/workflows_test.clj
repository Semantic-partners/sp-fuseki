(ns workflows-test
  "Tests for the build workflow. THIS IS THE POINT OF GENERATING IT.

  A compiler would catch an invalid expression context. It would never catch
  `latest` landing on the wrong Jena leg, publish being enabled for fork PRs, or
  an arm64 job scheduled onto a hosted runner. Those are semantics, and every one
  of them is an assertion against a map.

  Each test below corresponds to something that actually broke, or to an
  invariant whose violation would be silent and expensive.

  Run: bash test/unit.sh"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [sp-fuseki.workflows :as w]))

(def wf w/build-workflow)

(defn- job [id] (get-in wf [:jobs id]))

(defn- step-named
  "Find a step by name, action, or script content — so a test can key off the
  thing it actually cares about rather than the step's label."
  [job-id needle]
  (->> (:steps (job job-id))
       (filter #(str/includes? (str (:name %) (:uses %) (:run %)) needle))
       first))

(defn- tags [] (get-in (step-named :merge "metadata-action") [:with :tags]))

(defn- tag-line [substr]
  (->> (str/split-lines (tags))
       (filter #(str/includes? % substr))
       first))

;; ---------------------------------------------------------------------------
;; Tag rules. Getting these wrong publishes a wrong `latest` — silently.
;; ---------------------------------------------------------------------------

(deftest latest-only-for-the-default-leg-on-main
  (let [line (tag-line "value=latest")]
    (is (some? line))
    (testing "guarded on all three conditions, not just one"
      (is (str/includes? line "matrix.jena == needs.plan.outputs.default"))
      (is (str/includes? line "github.ref == 'refs/heads/main'"))
      (is (str/includes? line "github.event_name != 'pull_request'")))))

(deftest release-tags-never-published-from-a-pr
  (doseq [t ["value=${{ matrix.jena }}-${{ github.run_number }}" "value=${{ matrix.jena }},"]]
    (let [line (tag-line t)]
      (is (some? line) (str "missing tag rule: " t))
      (is (str/includes? line "enable=${{ github.event_name != 'pull_request' }}")
          (str t " must be disabled on PRs")))))

(deftest prs-get-a-disposable-tag-only
  (let [line (tag-line "value=pr-")]
    (is (str/includes? line "github.event.number"))
    (is (str/includes? line "enable=${{ github.event_name == 'pull_request' }}"))))

;; ---------------------------------------------------------------------------
;; Who is allowed to publish. A fork PR must never get our registry.
;; ---------------------------------------------------------------------------

(deftest publishing-jobs-exclude-fork-prs
  (doseq [id [:publish :merge]]
    (let [cond- (:if (job id))]
      (is (str/includes? cond- "github.event.pull_request.head.repo.full_name == github.repository")
          (str id " must require a same-repo PR"))
      (testing "and still runs on push"
        (is (str/includes? cond- "github.event_name != 'pull_request'"))))))

(deftest scanning-and-signing-stay-off-prs
  (doseq [needle ["trivy" "cosign sign"]]
    (let [s (step-named :merge needle)]
      (is (some? s) (str "no step matching " needle))
      (is (= w/not-pr (:if s)) (str needle " should be gated to non-PR events")))))

(deftest cosign-installer-shares-the-signing-gate
  ;; One gated and the other not is the bug: we installed cosign on every PR,
  ;; never signed there, and a failed cosign download took out a merge leg.
  (let [installer (step-named :merge "cosign-installer")
        signer    (step-named :merge "cosign sign")]
    (is (some? installer))
    (is (= (:if signer) (:if installer))
        "installing cosign must be conditioned exactly like signing with it")))

(deftest tests-gate-publishing
  (is (some #{"test"} (:needs (job :publish)))
      "publish must depend on test, or a red suite still ships an image"))

;; ---------------------------------------------------------------------------
;; Runner targeting. A wrong label HANGS, it doesn't fail — so assert it.
;; ---------------------------------------------------------------------------

(deftest arm64-runs-on-the-self-hosted-mac-amd64-on-hosted
  (doseq [id [:test :publish]]
    (let [ro (:runs-on (job id))]
      (is (str/includes? ro "matrix.arch == 'arm64'"))
      (is (str/includes? ro w/hosted) "amd64 must fall back to the hosted runner")
      (testing "labels, not the runner's name — a name matches nothing"
        (is (not (str/includes? ro "macanton-sp")))))))

(deftest the-runner-label-array-is-valid-json
  ;; fromJSON() parses JSON, not YAML. Emitting YAML flow style here produced
  ;; `[self-hosted, macOS, ARM64] ` — accepted by no one, and it would have
  ;; surfaced as a runtime failure on every arm64 job.
  (doseq [id [:test :publish]]
    (let [[_ arr] (re-find #"fromJSON\('(\[.*?\])'\)" (:runs-on (job id)))]
      (is (some? arr))
      (is (= w/mac-arm64 (json/parse-string arr))
          "must round-trip as JSON to exactly the label set"))))

;; ---------------------------------------------------------------------------
;; Structure we rely on elsewhere
;; ---------------------------------------------------------------------------

(deftest unit-tests-run-before-the-smoke-suite
  (let [names (->> (:steps (job :test)) (map #(or (:name %) (:uses %))) vec)
        idx   (fn [needle] (first (keep-indexed #(when (str/includes? %2 needle) %1) names)))]
    (is (< (idx "Unit tests") (idx "smoke")) "fast tests first, or feedback is slow for nothing")))

(deftest publish-pushes-by-digest-and-applies-no-tags
  (let [with- (:with (step-named :publish "Build + push by digest"))]
    (is (str/includes? (:outputs with-) "push-by-digest=true"))
    (is (nil? (:tags with-)) "tagging belongs to merge alone")))

(deftest merge-refuses-a-single-arch-manifest
  (let [s (step-named :merge "Verify both architectures")]
    (is (some? s))
    (doseq [want ["linux/amd64" "linux/arm64"]]
      (is (str/includes? (:run s) want)))))

(deftest no-cron-schedule
  ;; Deliberate: build on bumps, not on a clock. If someone re-adds a cron they
  ;; should have to change this test and think about the runner cost.
  (is (nil? (get-in wf [:on :schedule]))))

(deftest concurrency-supersedes-older-runs
  (is (true? (get-in wf [:concurrency :cancel-in-progress]))
      "two runs racing a ref can land `latest` on the older commit"))

(deftest every-job-declares-a-runner
  (doseq [[id j] (:jobs wf)]
    (is (some? (:runs-on j)) (str id " has no runs-on"))))

;; ---------------------------------------------------------------------------
;; The validator itself — these are the four failures from the session, encoded
;; so they can't come back.
;; ---------------------------------------------------------------------------

(defn- refuses? [wf-map]
  (try (w/validate! wf-map) nil
       (catch Exception e (ex-message e))))

(deftest the-real-workflow-validates
  (is (some? (w/validate! wf))))

(deftest rejects-runner-context-in-job-level-env
  ;; The one that invalidated the whole file and produced a zero-job run.
  (let [msg (refuses? {:jobs {:x {:runs-on w/hosted
                                  :env {:DOCKER_CONFIG "${{ runner.temp }}/.docker"}}}})]
    (is (str/includes? msg "runner"))
    (is (str/includes? msg "invalidates the whole workflow file"))))

(deftest rejects-an-unknown-runner-label-set
  ;; `macanton-sp` is a NAME. Requesting it queued jobs forever.
  (let [msg (refuses? {:jobs {:x {:runs-on (format "${{ matrix.a == 'b' && fromJSON('%s') || '%s' }}"
                                                   "[\"self-hosted\", \"macanton-sp\"]" w/hosted)
                                  :strategy {:matrix {:a ["b"]}}}}})]
    (is (str/includes? msg "aren't a known runner"))
    (is (str/includes? msg "hangs"))))

(deftest rejects-a-needs-that-points-nowhere
  (is (str/includes? (refuses? {:jobs {:x {:runs-on w/hosted :needs ["ghost"]}}})
                     "not a job in this workflow")))

(deftest rejects-a-matrix-key-that-was-never-declared
  (is (str/includes? (refuses? {:jobs {:x {:runs-on w/hosted
                                           :steps [{:run "echo ${{ matrix.nope }}"}]}}})
                     "references matrix.nope")))

(deftest rejects-multi-command-shell-without-set-euo-pipefail
  (is (str/includes? (refuses? {:jobs {:x {:runs-on w/hosted
                                           :steps [{:name "s" :run "echo one\necho two\n"}]}}})
                     "set -euo pipefail"))
  (testing "but a single command continued across lines is fine"
    (is (nil? (refuses? {:jobs {:x {:runs-on w/hosted
                                    :steps [{:name "s" :run "docker run \\\n  --rm hello\n"}]}}})))))

;; ---------------------------------------------------------------------------
;; Emission
;; ---------------------------------------------------------------------------

(deftest emitted-yaml-is-labelled-generated
  (let [out (w/->yaml wf)]
    (is (str/starts-with? out "# GENERATED"))
    (is (str/includes? out "DO NOT EDIT"))
    (testing "and points at how to regenerate"
      (is (str/includes? out "bb ci/generate.clj")))
    (testing "'on' is quoted so YAML 1.1 can't read it as boolean true"
      (is (str/includes? out "'on':")))))
