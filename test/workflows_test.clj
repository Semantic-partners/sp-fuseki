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

(deftest publishing-side-effects-stay-off-prs
  ;; The REPORT and the SIGNATURE are side effects of publishing a release, so
  ;; they stay off PRs. The scan GATE is not a side effect — it's feedback, and it
  ;; deliberately runs on PRs (see trivy-blocks-only-on-fixable-findings).
  (doseq [[needle desc] [["full report" "the SARIF report"]
                         ["cosign sign" "signing"]]]
    (let [s (step-named :merge needle)]
      (is (some? s) (str "no step matching " needle))
      (is (str/includes? (:if s) w/not-pr) (str desc " should be gated to non-PR events")))))

(deftest fork-prs-never-reach-the-self-hosted-runner
  ;; The one that matters if this repo goes public. A self-hosted runner executes
  ;; whatever the workflow says, on a real machine with persistent state and LAN
  ;; access, so a fork PR must never land there. amd64 still runs for forks on
  ;; disposable hosted VMs, so outside contributions are still tested.
  ;;
  ;; Enforced by OMITTING the arch, not by a job-level `if`: `runs-on` resolves
  ;; before steps run, so a job whose steps are all skipped is still scheduled
  ;; onto the machine. (And job-level `if` can't see `matrix` anyway.)
  (testing "the arch list is computed, not hardcoded"
    (doseq [id [:test :publish]]
      (is (= w/arches-matrix (get-in (job id) [:strategy :matrix :arch]))
          (str id " must take its arch list from plan"))))
  (testing "plan decides it from whether the event is same-repo"
    (is (str/includes? (get-in (job :plan) [:env :SAME_REPO]) "head.repo.full_name == github.repository"))
    (let [script (:run (second (:steps (job :plan))))]
      (is (str/includes? script "SAME_REPO"))
      (is (str/includes? script "[\"amd64\"]") "fork PRs get amd64 only")
      (is (str/includes? script "arches=") "and it must be exported for the matrices")))
  (testing "publish never runs for a fork at all"
    (is (str/includes? (:if (job :publish)) "head.repo.full_name == github.repository"))))

(deftest a-public-repo-refuses-to-use-the-self-hosted-runner
  ;; Issue #9's two org settings are checkboxes; this is the part that can't be
  ;; un-ticked by accident. Failing beats silently dropping arm64 — the merge job
  ;; would then reject the single-arch manifest with a more confusing error.
  (let [script (:run (second (:steps (job :plan))))]
    (is (str/includes? (get-in (job :plan) [:env :IS_PUBLIC]) "repository.private == false")
        "plan must know whether the repo is public")
    (is (str/includes? script "IS_PUBLIC")
        "and act on it")
    (is (str/includes? script "::error::")
        "with a GitHub-annotated error, not a bare exit")
    (is (str/includes? script "issue #9")
        "naming where the decision lives")))

(deftest cosign-is-installed-by-us-with-verification
  ;; sigstore/cosign-installer single-shots a GitHub release download, which
  ;; failed this pipeline twice (exit 56, exit 22).
  (let [uses (->> (:steps (job :merge)) (keep :uses) (filter #(str/includes? % "cosign")))
        step (step-named :merge "Install cosign")]
    (is (empty? uses) "no third-party cosign installer action")
    (is (some? step))
    (testing "patient retries, because release downloads are the flakiest thing here"
      (is (str/includes? (:run step) "--retry-all-errors"))
      (is (str/includes? (:run step) "--retry-max-time")))
    (testing "checksum-verified — a signing tool must not be a partial download"
      (is (str/includes? (:run step) "sha256sum -c")))
    (testing "version pinned in the SOURCE, so Renovate can't edit generated YAML"
      (is (str/includes? (:run step) "${COSIGN_VERSION}"))
      (is (= w/cosign-version (get-in step [:env :COSIGN_VERSION]))))))

(deftest the-credential-file-is-removed-even-on-failure
  (let [step (step-named :publish "Remove the credential file")]
    (is (some? step))
    (is (= "always()" (:if step))
        "a cancelled job on a shared machine shouldn't leave a token on disk")))

(deftest installing-cosign-shares-the-signing-gate
  ;; One gated and the other not is the bug: we installed cosign on every PR,
  ;; never signed there, and a failed cosign download took out a merge leg.
  (let [installer (step-named :merge "Install cosign")
        signer    (step-named :merge "cosign sign")]
    (is (some? installer))
    (is (= (:if signer) (:if installer))
        "installing cosign must be conditioned exactly like signing with it")))

(deftest trivy-blocks-only-on-fixable-findings
  ;; Measured 2026-08-12: 35 HIGH/CRITICAL, zero with a fix available. Blocking on
  ;; severity alone would be permanently red with nothing actionable.
  (let [gate (step-named :merge "fail only on vulnerabilities")]
    (is (some? gate))
    (is (true? (get-in gate [:with :ignore-unfixed])) "unfixable findings must not block")
    (is (= "1" (get-in gate [:with :exit-code])) "fixable findings MUST block")
    (is (nil? (:continue-on-error gate))
        "a gate with continue-on-error is decoration, which is what this replaced")
    (testing "and it runs on PRs — telling a contributor beats telling main"
      (is (nil? (:if gate))))))

(deftest the-sbom-is-produced-and-then-checked
  ;; `sbom: true` is the intention; the merge job checks the artifact. The README
  ;; tells people to query it instead of shelling into the image, so an empty or
  ;; missing SBOM would make a documented workflow silently useless.
  (let [build (step-named :publish "Build + push by digest")
        check (step-named :merge "Verify the SBOM")]
    (is (true? (get-in build [:with :sbom])) "publish must attach an SBOM")
    (is (true? (get-in build [:with :provenance])) "and provenance")
    (is (some? check) "merge must verify it")
    (testing "on both architectures, not just the one that happens to be first"
      (is (str/includes? (:run check) "linux/amd64"))
      (is (str/includes? (:run check) "linux/arm64")))
    (testing "and asserts a package the healthcheck actually depends on"
      (is (str/includes? (:run check) "curl")))))

(deftest the-full-report-never-blocks-and-is-kept
  (let [report (step-named :merge "full report")
        keep-  (step-named :merge "Keep the report")]
    (is (= "0" (get-in report [:with :exit-code])) "the report must never fail a build")
    (is (= "sarif" (get-in report [:with :format])))
    (testing "and it leaves the runner, or it may as well not exist"
      (is (some? keep-))
      (is (= "trivy.sarif" (get-in keep- [:with :path]))))))

(deftest code-scanning-upload-activates-itself-when-public
  ;; Uploading needs Advanced Security on private repos. Rather than a step that
  ;; is expected to fail, it turns itself on when the repo goes public.
  (let [up (step-named :merge "code scanning")]
    (is (some? up))
    (is (str/includes? (:if up) "github.event.repository.private == false"))
    (is (nil? (:continue-on-error up)) "no expected-to-fail steps"))
  (testing "and the job can write the results"
    (is (= "write" (get-in (job :merge) [:permissions :security-events])))))

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

(deftest rejects-matrix-in-a-job-level-if
  ;; My attempt to gate one matrix leg. Job-level `if` sees only github/needs/
  ;; inputs/vars — actionlint caught this before the generator did, so now the
  ;; generator does too.
  (let [msg (refuses? {:jobs {:x {:runs-on w/hosted
                                  :if "matrix.arch != 'arm64'"
                                  :strategy {:matrix {:arch ["amd64"]}}}}})]
    (is (str/includes? msg "matrix"))
    (is (str/includes? msg "isn't available there"))))

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
