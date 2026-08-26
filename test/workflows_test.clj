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

(deftest the-build-axis-is-calver-plus-the-commit
  ;; `6.2.0-2026.08.26-06f7922`. Jena's version leads because that is what anyone
  ;; is choosing; the suffix answers two other questions — how stale, and built
  ;; from what. It replaced `github.run_number`, which answered neither: a counter
  ;; that advances for runs which changed nothing.
  (let [script (:run (second (:steps (job :plan))))]
    (testing "computed once in plan, so every Jena leg of one run agrees"
      (is (str/includes? script "BUILD=") "plan must compute it")
      (is (str/includes? script "echo \"build=$BUILD\"") "and export it")
      (is (= "${{ steps.p.outputs.build }}" (get-in (job :plan) [:outputs :build]))))
    (testing "UTC — a local-time stamp is ambiguous twice a year"
      (is (str/includes? script "date -u")))
    (testing "date then sha, so the tag sorts by day and names its tree"
      (is (str/includes? script "+%Y.%m.%d"))
      (is (str/includes? script "${GITHUB_SHA::7}")))
    (testing "and no counter anywhere in the tags"
      (is (not (str/includes? (tags) "run_number")))))
  (testing "the jena version leads the tag, not the date"
    (is (str/includes? (tag-line "needs.plan.outputs.build")
                       "value=${{ matrix.jena }}-"))))

(deftest latest-only-for-the-default-leg-on-main
  (let [line (tag-line "value=latest")]
    (is (some? line))
    (testing "guarded on all three conditions, not just one"
      (is (str/includes? line "matrix.jena == needs.plan.outputs.default"))
      (is (str/includes? line "github.ref == 'refs/heads/main'"))
      (is (str/includes? line "github.event_name != 'pull_request'")))))

(deftest release-tags-never-published-from-a-pr
  (doseq [t ["value=${{ matrix.jena }}-${{ needs.plan.outputs.build }}" "value=${{ matrix.jena }},"]]
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

(deftest no-job-can-land-on-a-self-hosted-runner
  ;; The property that replaced a whole routing mechanism. arm64 used to run on our
  ;; own Apple Silicon box, which meant the workflow had to know whether the repo
  ;; was public and whether the PR came from a fork, and drop the arm64 leg when it
  ;; could not be trusted — enforced by omitting the arch, because `runs-on`
  ;; resolves before steps run and a job with every step skipped is still scheduled
  ;; onto the machine.
  ;;
  ;; None of that exists now. Both legs are GitHub's, so a fork PR gets a
  ;; disposable VM like everyone else, and the thing being guarded is simply gone.
  ;; This test is what stops it coming back by accident.
  (doseq [id [:plan :test :publish :merge]]
    (let [ro (str (:runs-on (job id)))]
      (is (not (str/includes? ro "self-hosted"))
          (str id ": a self-hosted runner would hand a fork PR a real machine"))))
  (testing "and nothing in the rendered YAML mentions one, including inside shell"
    ;; Against the rendered text, not the job maps: a label that only ever appeared
    ;; in a `run:` heredoc would pass the check above and still schedule a job.
    (is (not (str/includes? (w/->yaml wf) "self-hosted"))))
  (testing "publish still never runs for a fork — it holds a package-write token"
    (is (str/includes? (:if (job :publish)) "head.repo.full_name == github.repository"))))

(deftest both-arches-always-run
  ;; The arch list was a `plan` output while it had to shrink for untrusted events.
  ;; It is a constant now, and a constant is one fewer runtime value to get wrong.
  (doseq [id [:test :publish]]
    (is (= ["amd64" "arm64"] (get-in (job id) [:strategy :matrix :arch]))
        (str id " must build and test both arches"))))

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

(deftest arm64-runs-on-the-hosted-arm-runner-amd64-on-the-hosted-one
  (doseq [id [:test :publish]]
    (let [ro (:runs-on (job id))]
      (is (str/includes? ro "matrix.arch == 'arm64'"))
      (is (str/includes? ro w/hosted-arm) "arm64 must name GitHub's arm runner")
      (is (str/includes? ro w/hosted) "amd64 must fall back to the hosted runner"))))

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
  ;; A runner's NAME is not a label, and requesting one queued two jobs forever
  ;; with `runner=` empty. Nothing fails; the run simply never starts, which is why
  ;; generation refuses rather than trusting the string.
  (let [msg (refuses? {:jobs {:x {:runs-on (format "${{ matrix.a == 'b' && fromJSON('%s') || '%s' }}"
                                                   "[\"self-hosted\", \"some-runner-name\"]" w/hosted)
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
