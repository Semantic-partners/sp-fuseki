(ns sp-fuseki.workflows
  "The build workflow, as data. Generates .github/workflows/build.yml.

  WHY THIS EXISTS. GitHub Actions YAML is a programming language with no
  compiler and, worse, nothing to test. One session produced four failures that
  no YAML validator could see:

    1. `${{ runner.temp }}` in job-level `env:` — invalid by context-availability
       rules, and it invalidated the ENTIRE file. Symptom: a run with zero jobs
       and the message \"This run likely failed because of a workflow file issue\".
    2. A runner label that matched nothing — jobs QUEUED FOREVER rather than
       failing, because nothing resolves labels up front.
    3. Conditional runners via `x && fromJSON('[...]') || 'y'` — a ternary
       returning two different types, inside string templating.
    4. Shell embedded in YAML: `docker logs | grep -q` trips pipefail via
       SIGPIPE; an unindented heredoc terminator silently ends the block scalar.

  A compiler would have caught 1 and 3. Only tests catch the ones that actually
  matter: `latest` applied to the wrong Jena leg, publish accidentally enabled
  for fork PRs, an arm64 job pointed at a hosted runner. Those are semantic, and
  they're one assertion each against a Clojure map — see test/workflows_test.clj.

  The generated YAML is not the document any more; this file is. Comments here
  are the explanation, which is why they're long — YAML generators drop comments,
  and that knowledge is worth more than the formatting it used to live in.

  Regenerate: bb ci/generate.clj        (the pre-commit hook does this)
  Verify:     bb ci/generate.clj --check"
  (:require [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.string :as str]))

(defn m
  "array-map, so emitted key order is the order written here."
  [& kvs]
  (apply array-map kvs))

;; ---------------------------------------------------------------------------
;; Runners
;; ---------------------------------------------------------------------------

(def hosted "ubuntu-latest")

(def hosted-arm
  "GitHub's hosted arm64 runner. Free on public repositories, ordinary Actions
  minutes on private ones — availability is not the constraint, billing is.

  Corrected here rather than left as folklore: the earlier note in this file said
  the label was public-only and would fail outright, and I made that worse by
  claiming it would HANG. Neither is true. Run 32657188563 scheduled and completed
  both arm64 legs on this repo while it was still private, and the timing API
  reported 0s billable for the whole run."
  "ubuntu-24.04-arm")

(def known-runners
  "Anything else is a typo, and a typo hangs rather than fails. Generation
  refuses instead."
  #{hosted hosted-arm})

(defn runner-for
  "amd64 on hosted, arm64 on hosted-arm. A ternary because `runs-on` cannot be a
  matrix lookup.

  This used to be a JSON value computed at runtime by `plan`, because arm64 ran on
  our own Apple Silicon box and the label set had to change with repo visibility
  and fork status. Both legs are GitHub's now: no self-hosted runner means no
  persistent state, no LAN, and nothing to fence a fork PR away from — the risk is
  gone rather than managed, and the workflow is one expression shorter for it."
  [arch-expr]
  (format "${{ %s == 'arm64' && '%s' || '%s' }}" arch-expr hosted-arm hosted))

;; ---------------------------------------------------------------------------
;; Shared expressions
;; ---------------------------------------------------------------------------

(def not-pr "github.event_name != 'pull_request'")

;; Pinned here rather than in the generated YAML: a Renovate PR editing
;; build.yml directly would be reverted by the next regeneration AND fail the
;; drift check. Generated files must never be a bot's target — the source is.
;; renovate: datasource=github-releases depName=sigstore/cosign extractVersion=^v(?<version>.*)$
(def cosign-version "2.5.2")

(def same-repo-or-push
  "Publish and merge run on same-repo PRs so the whole path is proven BEFORE
  merge — three merge-to-test cycles earned this. Fork PRs get no packages:write
  token, so they build and test only, which matters now the README invites PRs."
  (str not-pr " || github.event.pull_request.head.repo.full_name == github.repository"))

(def jena-matrix "${{ fromJSON(needs.plan.outputs.matrix) }}")
(def arches-matrix
  "Both arches, always. This was a `plan` output while arm64 ran on our own
  hardware and a fork PR had to be dropped to amd64 only; with both legs on
  GitHub's runners there is nothing left to decide at runtime."
  ["amd64" "arm64"])
(def image "${{ needs.plan.outputs.image }}")

(defn cache-scope [] "type=gha,scope=jena-${{ matrix.jena }}-${{ matrix.arch }}")

;; ---------------------------------------------------------------------------
;; Jobs
;; ---------------------------------------------------------------------------

(def plan-job
  (m :runs-on hosted
     ;; EXTRA_JENA lists older versions still published beside the Dockerfile's
     ;; pin. The pin is Renovate-managed and is the leg that gets `latest`, so
     ;; there is one source of truth for "what is current".
     ;;
     ;; EXTRA_JENA is EMPTY on purpose. It held 6.1.0 until the CVE gate found two
     ;; HIGH findings WITH fixes available in that image's bundled jars —
     ;; shiro-core 2.1.0 (CVE-2026-49268, LDAP injection into a DN) and
     ;; jetty-security 12.1.8 (CVE-2026-10050, Digest auth). Both are fixed in the
     ;; jars Jena 6.2.0 ships. The argument for the matrix was that older legs get
     ;; MAINTAINED; a leg we can't patch is being kept warm, not maintained — and
     ;; shipping a fixable CVE in the auth layer undercuts the whole claim.
     ;;
     ;; The mechanism stays: add a version here to publish it alongside the
     ;; Dockerfile's pin, and the gate will tell you if it's patchable.
     :env (m :EXTRA_JENA "")
     :outputs (m :default "${{ steps.p.outputs.default }}"
                 :matrix "${{ steps.p.outputs.matrix }}"
                 :build "${{ steps.p.outputs.build }}"
                 :image "${{ steps.p.outputs.image }}")
     :steps [(m :uses "actions/checkout@v4")
             (m :id "p"
                :run (str "set -euo pipefail\n"
                          "DEF=\"$(sed -n 's/^ARG JENA_VERSION=//p' image/Dockerfile | head -1)\"\n"
                          "[ -n \"$DEF\" ] || { echo \"could not read ARG JENA_VERSION from image/Dockerfile\" >&2; exit 1; }\n"
                          "# EXTRA_JENA is a space-separated list, so the split is deliberate.\n"
                          "# shellcheck disable=SC2086\n"
                          "MATRIX=\"$(printf '%s\\n' $EXTRA_JENA \"$DEF\" | sed '/^$/d' | sort -u -V | jq -R . | jq -sc .)\"\n"
                          "OWNER=\"$(echo '${{ github.repository_owner }}' | tr '[:upper:]' '[:lower:]')\"\n"
                          "# CalVer plus the commit, computed ONCE so every Jena leg of a run agrees.\n"
                          "# UTC: a stamp in local time is ambiguous twice a year and wrong to anyone\n"
                          "# reading it from another zone. The sha makes the tag traceable to a tree,\n"
                          "# and means a re-run of one commit produces the same tag rather than a\n"
                          "# second name for identical bytes.\n"
                          "BUILD=\"$(date -u +%Y.%m.%d)-${GITHUB_SHA::7}\"\n"
                          "{\n"
                          "  echo \"default=$DEF\"\n"
                          "  echo \"matrix=$MATRIX\"\n"
                          "  echo \"build=$BUILD\"\n"
                          "  # GHCR requires a lowercase image name.\n"
                          "  echo \"image=${REGISTRY}/${OWNER}/sp-fuseki\"\n"
                          "} >> \"$GITHUB_OUTPUT\"\n"
                          "echo \"jena legs: $MATRIX  (latest -> $DEF)\"\n"))]))

(def test-job
  ;; Each arch built and tested by a runner of that arch. Before this, arm64 was
  ;; only ever an emulated leg of a publish — never tested at all.
  ;;
  ;; Both legs run for every event, fork PRs included: they are GitHub-hosted
  ;; VMs, disposable and holding nothing, so there is nothing an untrusted
  ;; workflow could reach. Publishing is still gated to same-repo events — that
  ;; job carries a package-write token, which is worth guarding.
  (m :needs "plan"
     :runs-on (runner-for "matrix.arch")
     :strategy (m :fail-fast false
                  :matrix (m :jena jena-matrix :arch arches-matrix))
     :steps [(m :uses "actions/checkout@v4")
             (m :uses "docker/setup-buildx-action@v3")
             (m :name "Build (native, load)"
                :uses "docker/build-push-action@v6"
                :with (m :context "."
                         :file "image/Dockerfile"
                         :load true
                         :tags "sp-fuseki:ci-${{ matrix.jena }}"
                         :platforms "linux/${{ matrix.arch }}"
                         :build-args "JENA_VERSION=${{ matrix.jena }}\n"
                         ;; Shared with publish, so JRE/Fuseki/babashka download
                         ;; once per leg rather than once per job. Fewer requests
                         ;; is the real fix for the GitHub 503s; retries only
                         ;; papered over them.
                         :cache-from (cache-scope)
                         :cache-to (str/replace (cache-scope) "type=gha," "type=gha,mode=max,")))
             ;; Renderer contract, run with the bb that SHIPS in the image — no
             ;; setup action, no version skew. Sub-second, so it goes first.
             (m :name "Unit tests (fuseki.edn renderer + this workflow)"
                :run (str "docker run --rm -v \"$PWD:/w\" -w /w \\\n"
                          "  --entrypoint bash sp-fuseki:ci-${{ matrix.jena }} test/unit.sh\n"))
             (m :name "Packaging smoke test"
                :run "IMAGE=sp-fuseki:ci-${{ matrix.jena }} bash test/smoke.sh")]))

(def ^:private isolate-docker-step
  ;; Two bugs' worth of scar tissue, in order:
  ;;
  ;; The arm64 legs run on macOS, where Docker Desktop's credential helper is the
  ;; Keychain. A runner with no interactive session cannot write there — `docker
  ;; login` dies with "User interaction is not allowed. (-25308)". The test jobs
  ;; were fine because they never log in.
  ;;
  ;; Fixing that by pointing DOCKER_CONFIG at an empty dir then broke daemon
  ;; DISCOVERY: on macOS the socket is ~/.docker/run/docker.sock, found via the
  ;; docker *context*, which lives in the config dir that was just discarded. So
  ;; capture the endpoint from the real config FIRST, pin it as DOCKER_HOST, and
  ;; only then isolate credentials.
  ;;
  ;; Set via $GITHUB_ENV, not job-level `env:` — the `runner` context is not
  ;; available there and using it invalidates the whole file.
  (m :name "Isolate docker credentials, keep the daemon endpoint"
     :run (str "set -euo pipefail\n"
               "HOST=\"$(docker context inspect --format '{{.Endpoints.docker.Host}}')\"\n"
               "echo \"docker endpoint: $HOST\"\n"
               "mkdir -p \"$RUNNER_TEMP/.docker\"\n"
               "{\n"
               "  echo \"DOCKER_HOST=$HOST\"\n"
               "  echo \"DOCKER_CONFIG=$RUNNER_TEMP/.docker\"\n"
               "} >> \"$GITHUB_ENV\"\n")))

(def ^:private write-auth-step
  ;; Deliberately NOT docker/login-action: the macOS CLI selects the osxkeychain
  ;; helper BY DEFAULT, so even a fresh DOCKER_CONFIG routed the credential there
  ;; and failed again. Writing the auths entry ourselves uses no helper at all.
  ;; Job-scoped token, umask 077, temp dir discarded with the job.
  (m :name "Write registry credentials (no credential helper)"
     :env (m :GHCR_USER "${{ github.actor }}"
             :GHCR_TOKEN "${{ secrets.GITHUB_TOKEN }}")
     :run (str "set -euo pipefail\n"
               "AUTH=\"$(printf '%s:%s' \"$GHCR_USER\" \"$GHCR_TOKEN\" | base64 | tr -d '\\n')\"\n"
               "umask 077\n"
               "printf '{\"auths\":{\"%s\":{\"auth\":\"%s\"}}}\\n' \"$REGISTRY\" \"$AUTH\" \\\n"
               "  > \"$DOCKER_CONFIG/config.json\"\n"
               "echo \"wrote auth for $REGISTRY to $DOCKER_CONFIG/config.json\"\n")))

(def publish-job
  ;; Per arch, pushed BY DIGEST with no tags. No QEMU anywhere. Tags happen once,
  ;; in merge. Left parallel: the two arches share almost no blobs, so this isn't
  ;; the contention that made GHCR 403 a blob HEAD when same-arch pushes raced.
  (m :needs ["plan" "test"]
     :if same-repo-or-push
     :runs-on (runner-for "matrix.arch")
     :strategy (m :fail-fast false
                  :matrix (m :jena jena-matrix :arch arches-matrix))
     :permissions (m :contents "read" :packages "write")
     :steps [(m :uses "actions/checkout@v4")
             isolate-docker-step
             (m :uses "docker/setup-buildx-action@v3")
             write-auth-step
             (m :name "Build + push by digest"
                :id "build"
                :uses "docker/build-push-action@v6"
                :with (m :context "."
                         :file "image/Dockerfile"
                         :platforms "linux/${{ matrix.arch }}"
                         :build-args "JENA_VERSION=${{ matrix.jena }}\n"
                         :outputs (str "type=image,name=" image
                                       ",push-by-digest=true,name-canonical=true,push=true")
                         :provenance true
                         :sbom true
                         :cache-from (cache-scope)
                         :cache-to (str/replace (cache-scope) "type=gha," "type=gha,mode=max,")))
             (m :name "Stash the digest for the merge job"
                :run (str "set -euo pipefail\n"
                          "mkdir -p /tmp/digests\n"
                          "echo \"${{ steps.build.outputs.digest }}\" > \"/tmp/digests/${{ matrix.arch }}\"\n"))
             (m :uses "actions/upload-artifact@v4"
                :with (m :name "digest-${{ matrix.jena }}-${{ matrix.arch }}"
                         :path "/tmp/digests/*"
                         :retention-days 1
                         :if-no-files-found "error"))
             ;; RUNNER_TEMP is cleaned between jobs, but a cancelled job may not
             ;; get that far. The token expires with the job; the file should not
             ;; outlive it either.
             (m :name "Remove the credential file"
                :if "always()"
                :run (str "set -euo pipefail\n"
                          "[ -n \"${DOCKER_CONFIG:-}\" ] && rm -f \"$DOCKER_CONFIG/config.json\" || true\n"))]))

(def ^:private tag-lines
  ;; Release tags only off-PR; `latest` only for the default leg on main; a
  ;; disposable pr-<n> tag so a PR can exercise create/inspect/platform-assert
  ;; without touching a tag anyone consumes.
  ;;
  ;; The build axis is CalVer plus the commit — `6.2.0-2026.08.26-06f7922`. Jena's
  ;; version leads, because that is the thing anyone is actually choosing; ours is
  ;; a suffix answering two different questions. The date answers "how stale is
  ;; this image", which is the only question a rebuild of the SAME Jena raises: it
  ;; happens because a base layer or a CVE moved, not because we shipped a feature.
  ;; The sha answers "built from what", and makes a re-run of one commit produce
  ;; the same tag instead of a second name for identical bytes.
  ;;
  ;; It was `github.run_number` before, which is neither: a counter that advances
  ;; for runs that changed nothing and tells you nothing without a lookup.
  (str/join "\n"
            [(str "type=raw,value=${{ matrix.jena }}-${{ needs.plan.outputs.build }},enable=${{ " not-pr " }}")
             (str "type=raw,value=${{ matrix.jena }},enable=${{ " not-pr " }}")
             (str "type=raw,value=latest,enable=${{ " not-pr
                  " && matrix.jena == needs.plan.outputs.default"
                  " && github.ref == 'refs/heads/main' }}")
             "type=raw,value=pr-${{ github.event.number }}-${{ matrix.jena }},enable=${{ github.event_name == 'pull_request' }}"
             ""]))

(def merge-job
  ;; One manifest list per Jena leg, assembled from the per-arch digests. Tag
  ;; logic lives here ALONE, so `latest` cannot be applied twice or by one arch.
  (m :needs ["plan" "publish"]
     :if same-repo-or-push
     :runs-on hosted
     :strategy (m :fail-fast false :matrix (m :jena jena-matrix))
     :permissions (m :contents "read" :packages "write" :id-token "write"
                     ;; for the code-scanning upload
                     :security-events "write")
     :steps [(m :uses "actions/download-artifact@v4"
                :with (m :pattern "digest-${{ matrix.jena }}-*"
                         :merge-multiple true
                         :path "/tmp/digests"))
             (m :uses "docker/setup-buildx-action@v3")
             ;; Not sigstore/cosign-installer. It single-shots a GitHub release
             ;; download, which failed this pipeline twice — exit 56 (connection
             ;; reset) and exit 22 (HTTP error) — and GitHub release fetches have
             ;; been the flakiest dependency here by a distance. Same patient
             ;; backoff and checksum verification the Dockerfile uses for babashka,
             ;; and one less third-party action in the signing path.
             ;;
             ;; Gated identically to the signing step: installing a tool we don't
             ;; use is an avoidable network call, and an avoidable flake.
             (m :name (str "Install cosign " cosign-version)
                :if not-pr
                :env (m :COSIGN_VERSION cosign-version)
                :run (str "set -euo pipefail\n"
                          "base=\"https://github.com/sigstore/cosign/releases/download/v${COSIGN_VERSION}\"\n"
                          "curl -fsSL --retry 8 --retry-max-time 300 --retry-all-errors --connect-timeout 20 \\\n"
                          "  \"$base/cosign-linux-amd64\" -o \"$RUNNER_TEMP/cosign\"\n"
                          "curl -fsSL --retry 8 --retry-max-time 300 --retry-all-errors --connect-timeout 20 \\\n"
                          "  \"$base/cosign_checksums.txt\" -o \"$RUNNER_TEMP/cosign_checksums.txt\"\n"
                          "# A signing tool is the last thing that should be a partial download.\n"
                          "sum=\"$(grep -E ' cosign-linux-amd64$' \"$RUNNER_TEMP/cosign_checksums.txt\" | cut -d' ' -f1)\"\n"
                          "echo \"$sum  $RUNNER_TEMP/cosign\" | sha256sum -c -\n"
                          "chmod +x \"$RUNNER_TEMP/cosign\"\n"
                          "mkdir -p \"$RUNNER_TEMP/bin\"\n"
                          "mv \"$RUNNER_TEMP/cosign\" \"$RUNNER_TEMP/bin/cosign\"\n"
                          "echo \"$RUNNER_TEMP/bin\" >> \"$GITHUB_PATH\"\n"))
             (m :uses "docker/login-action@v3"
                :with (m :registry "${{ env.REGISTRY }}"
                         :username "${{ github.actor }}"
                         :password "${{ secrets.GITHUB_TOKEN }}"))
             (m :name "Metadata / two-axis tags"
                :id "meta"
                :uses "docker/metadata-action@v5"
                :with (m :images image :tags tag-lines))
             (m :name "Create the manifest list"
                :id "merge"
                :env (m :IMAGE image)
                :run (str "set -euo pipefail\n"
                          "ls -1 /tmp/digests\n"
                          "# -t per resolved tag, then one image@digest per arch. Both\n"
                          "# substitutions MUST word-split — they expand to argument lists.\n"
                          "# shellcheck disable=SC2046\n"
                          "docker buildx imagetools create \\\n"
                          "  $(jq -cr '.tags | map(\"-t \" + .) | join(\" \")' <<< \"$DOCKER_METADATA_OUTPUT_JSON\") \\\n"
                          "  $(for f in /tmp/digests/*; do printf '%s@%s ' \"$IMAGE\" \"$(cat \"$f\")\"; done)\n"
                          "# The manifest-list digest is what we scan and sign.\n"
                          "FIRST_TAG=\"$(jq -cr '.tags[0]' <<< \"$DOCKER_METADATA_OUTPUT_JSON\")\"\n"
                          "DIGEST=\"$(docker buildx imagetools inspect \"$FIRST_TAG\" --format '{{json .Manifest}}' | jq -r .digest)\"\n"
                          "echo \"digest=$DIGEST\" >> \"$GITHUB_OUTPUT\"\n"
                          "echo \"published $FIRST_TAG -> $DIGEST\"\n"))
             ;; A quietly single-arch `latest` is worse than a red build.
             (m :name "Verify both architectures are in the manifest"
                :env (m :IMAGE image :DIGEST "${{ steps.merge.outputs.digest }}")
                :run (str "set -euo pipefail\n"
                          "PLATFORMS=\"$(docker buildx imagetools inspect \"${IMAGE}@${DIGEST}\" --raw \\\n"
                          "  | jq -r '[.manifests[] | select(.platform.os==\"linux\") | \"\\(.platform.os)/\\(.platform.architecture)\"] | unique | join(\" \")')\"\n"
                          "echo \"platforms: $PLATFORMS\"\n"
                          "for want in linux/amd64 linux/arm64; do\n"
                          "  case \"$PLATFORMS\" in *\"$want\"*) ;; *) echo \"missing $want in the manifest\" >&2; exit 1 ;; esac\n"
                          "done\n"))
             ;; TWO scans, because "is there anything we could fix?" and "what is
             ;; in this image?" are different questions and only one should be able
             ;; to fail a build.
             ;;
             ;; The old single step wrote trivy.sarif to a runner that was then
             ;; destroyed — no upload, no artifact, continue-on-error, exit-code 0.
             ;; It produced the appearance of scanning and nothing else.
             ;;
             ;; Measured 2026-08-12: 35 HIGH/CRITICAL, of which ZERO have a fix
             ;; available (21 affected, 13 fix_deferred, 1 will_not_fix), all from
             ;; Debian 12 base packages; the Java scanner finds nothing. So blocking
             ;; on HIGH/CRITICAL would be red on every build forever with nothing
             ;; actionable — a wall, not a backlog.
             ;; Runs on PRs too, unlike the report below. A gate that only fires
             ;; after merge tells you about a fixable CVE once it's already on main;
             ;; on a PR it tells the contributor. ~20s, and the DB comes from a
             ;; registry rather than GitHub releases, so it's a different failure
             ;; domain from the download flakiness that plagued this pipeline.
             ;; The README tells people to query the SBOM instead of starting a
             ;; shell to find out what's installed. That is only true if the SBOM
             ;; is actually attached and actually lists packages — `sbom: true` on
             ;; the build step is the intention, this is the artifact.
             (m :name "Verify the SBOM is attached and populated"
                :env (m :IMAGE image :DIGEST "${{ steps.merge.outputs.digest }}")
                :run (str "set -euo pipefail\n"
                          "SBOM=\"$(docker buildx imagetools inspect \"${IMAGE}@${DIGEST}\" --format '{{json .SBOM}}')\"\n"
                          "for plat in linux/amd64 linux/arm64; do\n"
                          "  n=\"$(jq -r --arg p \"$plat\" '.[$p].SPDX.packages | length' <<< \"$SBOM\")\"\n"
                          "  echo \"$plat: $n packages\"\n"
                          "  [ \"$n\" -gt 50 ] || { echo \"::error::SBOM for $plat has $n packages — not populated\" >&2; exit 1; }\n"
                          "  jq -e --arg p \"$plat\" '[.[$p].SPDX.packages[].name] | index(\"curl\")' <<< \"$SBOM\" >/dev/null \\\n"
                          "    || { echo \"::error::SBOM for $plat does not list curl, which the healthcheck needs\" >&2; exit 1; }\n"
                          "done\n"))

             (m :name "Trivy — fail only on vulnerabilities that HAVE a fix"
                :uses "aquasecurity/trivy-action@v0.36.0"
                :with (m :image-ref (str image "@${{ steps.merge.outputs.digest }}")
                         :format "table"
                         ;; The whole gate: a patch exists and we are still shipping
                         ;; without it. Currently zero, so this goes in green and
                         ;; goes red the day that stops being true.
                         :ignore-unfixed true
                         :exit-code "1"
                         :severity "HIGH,CRITICAL"))

             ;; The full picture, unfixable included, as a report. Never blocks.
             (m :name "Trivy — full report (SARIF)"
                :if (str not-pr " && always()")
                :uses "aquasecurity/trivy-action@v0.36.0"
                :with (m :image-ref (str image "@${{ steps.merge.outputs.digest }}")
                         :format "sarif"
                         :output "trivy.sarif"
                         :exit-code "0"
                         :severity "HIGH,CRITICAL"))
             ;; Readable today, on a private repo, with no Advanced Security.
             (m :name "Keep the report"
                :if (str not-pr " && always()")
                :uses "actions/upload-artifact@v4"
                :with (m :name "trivy-${{ matrix.jena }}"
                         :path "trivy.sarif"
                         :retention-days 30
                         :if-no-files-found "warn"))
             ;; Code scanning needs Advanced Security on private repos, so this
             ;; activates itself when the repo goes public rather than sitting here
             ;; failing. Not continue-on-error: a step expected to fail is the kind
             ;; of decoration this change exists to delete.
             (m :name "Upload to code scanning (public repos only)"
                :if (str not-pr " && always() && github.event.repository.private == false")
                :uses "github/codeql-action/upload-sarif@v3"
                :with (m :sarif_file "trivy.sarif"
                         :category (str "trivy-${{ matrix.jena }}")))
             (m :name "Sign image (cosign keyless)"
                :if not-pr
                :env (m :IMG image :DIGEST "${{ steps.merge.outputs.digest }}")
                :run "cosign sign --yes \"${IMG}@${DIGEST}\"\n")]))

(def build-workflow
  (m :name "build"
     :on (m :push (m :branches ["main"] :tags ["v*"])
            :pull_request nil
            :workflow_dispatch nil)
     ;; No cron: we build on version bumps, not on a clock. Renovate raises the
     ;; PR and upstream-check catches what it misses.
     :env (m :REGISTRY "ghcr.io")
     ;; Two runs racing the same ref can land `latest` on the older commit's
     ;; default Jena. Newest push wins.
     :concurrency (m :group "build-${{ github.ref }}" :cancel-in-progress true)
     :jobs (m :plan plan-job
              :test test-job
              :publish publish-job
              :merge merge-job)))

;; ---------------------------------------------------------------------------
;; Validation — the failures above, made impossible rather than caught late
;; ---------------------------------------------------------------------------

(defn- expressions [x]
  (cond (string? x) (map second (re-seq #"\$\{\{(.*?)\}\}" x))
        (map? x) (mapcat expressions (vals x))
        (sequential? x) (mapcat expressions x)
        :else nil))

(defn- bad [& msg] (throw (ex-info (apply str msg) {:validation true})))

(defn validate!
  "Throw on anything that would fail — or worse, hang — at GitHub's end."
  [wf]
  (let [jobs (:jobs wf)]
    (doseq [[jid job] jobs]
      ;; 1. Context availability, per GitHub's table. Getting this wrong doesn't
      ;;    fail the job — it invalidates the WHOLE FILE, producing a run with zero
      ;;    jobs and "This run likely failed because of a workflow file issue".
      ;;
      ;;    Both entries below are scars: `runner` in job-level env cost a broken
      ;;    file, and `matrix` in a job-level `if` was my attempt to gate one
      ;;    matrix leg — impossible, and actionlint caught it before this did.
      ;;    (It wouldn't have worked regardless: `runs-on` resolves before steps,
      ;;    so the job would still be scheduled onto the runner.)
      (doseq [[k allowed] {:env #{"github" "needs" "strategy" "matrix" "secrets" "inputs" "vars"}
                           :if  #{"github" "needs" "inputs" "vars"}}]
        ;; `if:` is ALREADY an expression — GitHub evaluates it with or without
        ;; ${{ }}. Scanning only for ${{ }} (as the first version did) silently
        ;; skipped every bare `if`, which is how a test caught this validator
        ;; being wrong about the very rule it was added for.
        (doseq [e (if (= k :if)
                    (when-let [v (get job k)] [v])
                    (expressions (get job k)))
                [_ ctx] (re-seq #"\b([a-z]+)\." e)
                :when (and (not (allowed ctx))
                           (#{"runner" "steps" "env" "job" "matrix" "strategy" "secrets"} ctx))]
          (bad "job " jid ": job-level " k " uses the '" ctx
               "' context, which isn't available there (allowed: "
               (str/join ", " (sort allowed))
               ") — this invalidates the whole workflow file")))
      ;; 2. needs must exist, or the job silently never runs.
      (doseq [n (let [ns- (:needs job)] (if (string? ns-) [ns-] ns-))]
        (when-not (contains? jobs (keyword n))
          (bad "job " jid " needs '" n "', which is not a job in this workflow")))
      ;; 3. Unknown runner labels QUEUE FOREVER instead of failing.
      (let [ro (:runs-on job)]
        (when (and (string? ro) (not (str/includes? ro "${{")) (not (known-runners ro)))
          (bad "job " jid " runs-on '" ro "' is not a known runner"))
        (when (and (string? ro) (str/includes? ro "${{"))
          (doseq [labels (re-seq #"fromJSON\('(\[[^)]*\])'\)" ro)]
            ;; Parsed as JSON, the same way GitHub will parse it — so a
            ;; malformed array fails generation instead of the job.
            (let [parsed (json/parse-string (second labels))]
              (when-not (known-runners (vec parsed))
                (bad "job " jid " targets labels " (vec parsed)
                     " which aren't a known runner — a bad label hangs, it doesn't fail"))))))
      ;; 4. Matrix keys referenced must be declared.
      (let [declared (set (map name (keys (get-in job [:strategy :matrix]))))]
        (doseq [e (expressions (dissoc job :strategy))
                [_ k] (re-seq #"matrix\.([A-Za-z0-9_-]+)" e)]
          (when-not (declared k)
            (bad "job " jid " references matrix." k " but its matrix declares "
                 (or (seq declared) "nothing")))))
      ;; 5. Multi-command shell must fail loudly: without `set -e`, a failing
      ;;    command mid-script still leaves a green job. A single command spanning
      ;;    lines via `\` doesn't need it — the step's own exit code covers it,
      ;;    which is why this counts COMMANDS, not lines. (The first version
      ;;    counted lines and flagged a lone `docker run \` continuation.)
      (doseq [{:keys [run name]} (:steps job)
              :when run
              :let [commands (->> (str/replace run #"\\\n\s*" " ")
                                  str/split-lines
                                  (remove #(or (str/blank? %) (str/starts-with? (str/trim %) "#"))))]
              :when (> (count commands) 1)]
        (when-not (str/starts-with? run "set -euo pipefail")
          (bad "job " jid " step '" (or name "?") "' runs " (count commands)
               " commands but doesn't start with `set -euo pipefail`")))))
  wf)

;; ---------------------------------------------------------------------------
;; Emission
;; ---------------------------------------------------------------------------

(def header
  (str "# GENERATED by ci/sp_fuseki/workflows.clj — DO NOT EDIT.\n"
       "#\n"
       "# Edit the Clojure and regenerate (the pre-commit hook does it for you):\n"
       "#   bb ci/generate.clj\n"
       "#\n"
       "# Why generated: Actions YAML has no compiler and nothing to test. The\n"
       "# invariants that matter — `latest` only on the default leg, publish never\n"
       "# enabled for fork PRs, arm64 never on a hosted runner — are asserted in\n"
       "# test/workflows_test.clj. The reasoning lives in the source, at length.\n"
       "\n"))

(defn ->yaml [wf]
  (str header (yaml/generate-string (validate! wf) :dumper-options {:flow-style :block})))
