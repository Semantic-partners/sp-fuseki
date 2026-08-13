# sp-fuseki

Clean, **config-respecting**, **legible** Apache Jena Fuseki Docker images.

[![build](https://github.com/Semantic-partners/sp-fuseki/workflows/build/badge.svg?branch=main)](https://github.com/Semantic-partners/sp-fuseki/actions/workflows/build.yml)
[![lint](https://github.com/Semantic-partners/sp-fuseki/workflows/lint/badge.svg?branch=main)](https://github.com/Semantic-partners/sp-fuseki/actions/workflows/lint.yml)
[![upstream-check](https://github.com/Semantic-partners/sp-fuseki/workflows/upstream-check/badge.svg)](https://github.com/Semantic-partners/sp-fuseki/actions/workflows/upstream-check.yml)
[![license](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
![arch](https://img.shields.io/badge/arch-amd64%20%7C%20arm64-informational)
![signed](https://img.shields.io/badge/images-cosign%20signed-informational)

**`build` green means the tests passed** — unit tests over the config renderer and
over the CI definition itself, plus packaging assertions against real containers, on
**both** architectures natively. There's no separate tests badge because there's no
separate tests workflow: tests gate the build, so a green build *is* the claim, and
it can't drift from a count written in prose.
`upstream-check` runs weekly and opens an issue if Apache ships a newer Fuseki.

You mount config, we honour it. The boot path is a short, readable babashka
script — not five layers of entrypoint bash — and **every extension point is
documented below and exercised by the smoke test**. That tested legibility is
the point: you can understand and trust exactly what the container does.

**Our intent is that this is understandable and easy to configure — so if you
find it isn't, that's a bug, and we'd love to know.** Confusing default,
behaviour that surprised you, a doc that says one thing while the image does
another, an error message that sent you the wrong way: all fair game. Open an
issue, and **PRs are very welcome**. If you add or change an extension point,
add its assertion to [test/smoke.sh](test/smoke.sh) — that's what stops the
README drifting away from what the container actually does.

> **Status: v0.1, published to GHCR.** One image, both postures: **Fuseki's own
> UI ships enabled** (asserted by the smoke test), and `FUSEKI_UI=off` gives you
> the headless server. No separate `minimal`/`full` tags — it's a runtime switch,
> not a build variant. A bespoke SPARQL editor UI (RFC tier 2) is a later,
> separate decision — see [docs/RFC.md](docs/RFC.md).

This is *Semantic Partners' distribution* of Apache Jena Fuseki. Apache Jena is
Apache-2.0; this repackaging does not imply Apache endorsement. See [NOTICE](NOTICE).

## Quickstart

```bash
# Throwaway: a single in-memory dataset /ds (query + update + gsp-rw)
docker run --rm -p 3030:3030 ghcr.io/semantic-partners/sp-fuseki

# Your own datasets: mount an assembler config.ttl — honoured untouched
docker run --rm -p 3030:3030 \
  -v "$PWD/examples/config.ttl:/fuseki/config.ttl:ro" \
  ghcr.io/semantic-partners/sp-fuseki

# Basic auth, secret from the environment (or a file — see below)
docker run --rm -p 3030:3030 \
  -e FUSEKI_AUTH=basic -e FUSEKI_ADMIN_PASSWORD=changeme \
  ghcr.io/semantic-partners/sp-fuseki

# Headless: no UI, no admin area — same image
docker run --rm -p 3030:3030 -e FUSEKI_UI=off ghcr.io/semantic-partners/sp-fuseki

# Persistent (TDB2): mount a volume at /fuseki/databases — see Persistence below
docker run --rm -p 3030:3030 \
  -v sp-fuseki-data:/fuseki/databases \
  -v "$PWD/examples/config-tdb2.ttl:/fuseki/config.ttl:ro" \
  ghcr.io/semantic-partners/sp-fuseki
```

Fuseki's own UI is at <http://localhost:3030/>.

Load and query the default dataset:

```bash
curl -X POST -H 'Content-Type: text/turtle' \
  --data-binary @data.ttl 'http://localhost:3030/ds/data?default'
curl -G http://localhost:3030/ds/sparql --data-urlencode 'query=SELECT * { ?s ?p ?o }'
```

## Extension points (the contract)

Everything the image does is driven by these — nothing is hidden in a script.

### Mounts

| Mount | Default path | Behaviour |
|---|---|---|
| Assembler config | `/fuseki/config.ttl` | If present, **honoured untouched**. Wins over `fuseki.edn`. Never merged or silently regenerated. |
| EDN config | `/fuseki/fuseki.edn` | If present (and no `config.ttl`), validated and **rendered** to assembler TTL — see below. |
| Shiro auth config | `/fuseki/shiro.ini` | If present, **honoured untouched** (your escape hatch for any auth setup, incl. SOPS-decrypted secrets). If absent, generated from `FUSEKI_AUTH`. |
| **Persistent data** | `/fuseki/databases` | **Mount your volume here** for TDB2 datasets. Pre-created in the image and owned by uid 1000 — see below. |

The **effective** config and shiro that actually run are always written to
`$FUSEKI_BASE` (`config.effective.ttl`, `shiro.ini`) and their paths logged at
boot — so "what did it actually run" is never a mystery.

### Config: TTL or EDN, both first-class

**Bring a `config.ttl`** and it is simply used, untouched. No EDN gets in your way.

**Or write `fuseki.edn`** and get what hand-written assembler TTL can't give you
concisely — declared prefixes, TDB2 locations emitted for you, reasoner
selection, secrets via reader tags, and validation that refuses to boot rather
than half-configuring:

```clojure
{:auth     {:mode :anon}
 :prefixes {:ex "http://example.org/"}
 :datasets [{:name "kb"  :storage :tdb2 :endpoints #{:query :update :gsp-rw}}
            {:name "inf" :storage :mem  :endpoints #{:query :gsp-rw} :reasoner :rdfs}]}
```

```bash
docker run --rm -p 3030:3030 \
  -v "$PWD/examples/fuseki.edn:/fuseki/fuseki.edn:ro" \
  -v sp-fuseki-data:/fuseki/databases \
  ghcr.io/semantic-partners/sp-fuseki
```

Full example: [examples/fuseki.edn](examples/fuseki.edn). It is a **generator over
the assembler TTL**, never a replacement — anything the EDN can't express, drop to
TTL and mount that instead.

| | |
|---|---|
| `:datasets` | `:name`, `:storage` (`:mem`/`:tdb2`), `:endpoints` (`:query`/`:update`/`:gsp-rw`/`:gsp-r`), `:reasoner` (`:none`/`:rdfs`/`:owl-micro`) |
| `:prefixes` | keyword → IRI, declared once and emitted into the TTL |
| `:auth` | `{:mode :anon}` or `{:mode :basic}` |
| `:server` | `{:port 3030}` — honoured, and the container's healthcheck follows it |
| `:ui` | `{:enabled true}` |
| `#env "VAR"` / `#file "path"` | read a secret at boot — it never lives in the config |

**`:auth` and `:ui` are also env vars.** An explicitly set `FUSEKI_AUTH` /
`FUSEKI_UI` wins, then the EDN, then the default — and the resolved value is
logged **with its source** (`ui: off (from fuseki.edn :ui)`), so "why is the UI
off" never needs a bisect. They apply only when the EDN is the config source: a
mounted `config.ttl` means the EDN was ignored wholesale, and half-honouring an
ignored file would be worse than ignoring it.

**Precedence.** Both mounted → the TTL wins and the entrypoint **logs that the EDN
was ignored**; conflicting sources of truth are never a silent surprise. The
rendered result is always written to `$FUSEKI_BASE/config.effective.ttl` for you
to read. A malformed EDN is a `FATAL` at boot with the reason, not a container
that comes up missing a dataset.

What the EDN promises is specified in
[test/render_test.clj](test/render_test.clj) — those tests are the contract,
written to be read as documentation.

### Persistence — where to mount, and why it matters

Datasets are in-memory unless your config says otherwise. For TDB2, **mount a
volume at `/fuseki/databases` and point `tdb2:location` inside it**:

```bash
docker run --rm -p 3030:3030 \
  -v sp-fuseki-data:/fuseki/databases \
  -v "$PWD/examples/config-tdb2.ttl:/fuseki/config.ttl:ro" \
  ghcr.io/semantic-partners/sp-fuseki
```

[examples/config-tdb2.ttl](examples/config-tdb2.ttl) is a working starting point
(`tdb2:location "/fuseki/databases/ds"` — absolute, so it never depends on CWD).

**Mount there specifically.** The container runs as uid 1000, and a named volume
inherits the ownership of the path it covers. `/fuseki/databases` exists in the
image owned by `1000:1000`, so the volume is writable. Mount onto a path the
image *doesn't* create and Docker makes it `root:root`:

```
-v vol:/fuseki/databases   ->  drwxr-xr-x 1000 1000   # writable
-v vol:/some/other/path    ->  drwxr-xr-x    0    0   # Permission denied
```

The second case fails at boot with `AssemblerException: java.io.IOException: No
such file or directory` and exit code 1 — which reads like a broken config but is
purely permissions. If you hit that message, check ownership first.

`/fuseki/run` (`$FUSEKI_BASE`) also works and is where Fuseki keeps its own work
area — `backups/`, `logs/`, `configuration/`, plus our generated
`config.effective.ttl`. Mounting *that* persists data too, but mixes it with
regenerated boot files; prefer `/fuseki/databases` for data and mount
`/fuseki/run` only if you want `/$/backup` output to survive.

> **Bind mounts differ by platform.** On Docker Desktop (macOS/Windows) host
> ownership is remapped — a host directory owned by your user shows up inside the
> container as `1000:1000` and Just Works. On Linux the host uid is preserved, so
> a bind mount needs `chown 1000:1000` (or `--user`) or the same permission
> failure appears. A setup verified only on a Mac can still break on a Linux
> host; named volumes avoid the whole question.

### Environment

| Var | Default | Purpose |
|---|---|---|
| `FUSEKI_BASE` | `/fuseki/run` | Runtime/data dir (must be writable by uid 1000). |
| `FUSEKI_CONFIG` | `/fuseki/config.ttl` | Where to look for a mounted assembler config. |
| `FUSEKI_EDN` | `/fuseki/fuseki.edn` | Where to look for a mounted EDN config (used only if no `config.ttl`). |
| `FUSEKI_TDB2_ROOT` | `/fuseki/databases` | Directory `:tdb2` datasets are rendered under. |
| `FUSEKI_SHIRO` | `/fuseki/shiro.ini` | Where to look for a mounted shiro.ini. |
| `FUSEKI_PORT` | `3030` | Listen port. Also settable as `:server {:port n}` in `fuseki.edn`; env wins. The healthcheck follows whichever applied. |
| `FUSEKI_DATASET` | `ds` | Name of the generated default dataset (when no config mounted). |
| `FUSEKI_AUTH` | `anon` | `anon` (throwaway/lab) or `basic` (all endpoints require login, except `/$/ping` so the healthcheck works). |
| `FUSEKI_UI` | `on` | `on` serves Fuseki's own UI + admin area at `/`. `off` runs the headless server — no UI, no admin area, data endpoints unchanged. Same image either way. |
| `FUSEKI_ADMIN_USER` | `admin` | Basic-auth username. |
| `FUSEKI_ADMIN_PASSWORD` | — | Basic-auth secret, inline. |
| `FUSEKI_ADMIN_PASSWORD_FILE` | — | Basic-auth secret, read from a file (Docker/K8s secret, vault-agent sink, SOPS output). Preferred over the inline form; trailing newline trimmed. |

Credentials can also come from `fuseki.edn` as `:auth {:user … :password #env "…"}`
or `#file`. Env wins over the file, and the boot log names **which source** supplied
the secret — never the secret. All five paths (env, `*_FILE`, EDN `#env`, a missing
file, and a mounted `shiro.ini`) are covered by [smoke.sh](test/smoke.sh) §14–18.

**Secrets are backend-agnostic by design.** The image never bakes in a secrets
manager: a credential arrives via env, a `*_FILE` path, or your own mounted
`shiro.ini`. Whatever delivers it (Vault, SOPS, Docker secrets) just needs to
land it in one of those. Don't commit a plaintext password anywhere.

### The admin API (`/$/…`)

Fuseki's admin API creates and **deletes** datasets. Shiro matches on path, not
HTTP method, so `/$/datasets` can't be read-open and write-closed — it's one
path. We resolve that as follows:

| Mode | Data endpoints | Read-only admin (`/$/ping`, `/$/server`, `/$/stats`, `/$/metrics`) | Mutating admin (`/$/datasets`, `/$/backup`, `/$/compact`, `/$/tasks`) |
|---|---|---|---|
| `FUSEKI_AUTH=anon` (default) | open | open | **401** — no credentials exist to satisfy, so admin is closed |
| `FUSEKI_AUTH=basic` | login | login (`/$/ping` open for the healthcheck) | login |

So the default image is a usable lab server whose datasets a passer-by **cannot
drop**, and the UI still shows server info and dataset lists. If you want the
UI's dataset-management pages to work, run `FUSEKI_AUTH=basic` and log in. Both
behaviours are asserted by the smoke test.

Need something else — LDAP, per-dataset rules, localhost-only admin? Mount your
own `shiro.ini`; it's honoured untouched and nothing above applies.

### Adding a dataset

It's Fuseki's own assembler vocabulary — add a `fuseki:Service`. See
[examples/config.ttl](examples/config.ttl). No bespoke API to learn.

## Defaults

Non-root (uid 1000) · healthcheck on `/$/ping` · Fuseki's UI on · mutating admin
API fenced · in-memory datasets unless your config says TDB2 · multi-arch
amd64+arm64 · pinned Jena from `archive.apache.org`.

## Vulnerability posture — yes, we know about the CVEs

Scan this image and you will find HIGH and CRITICAL findings. We would rather tell
you than have you discover them and wonder what else we haven't mentioned.

As of **2026-08-12**, on the Jena 6.2.0 image:

| | |
|---|---|
| HIGH / CRITICAL | **35** (30 HIGH, 5 CRITICAL) |
| **With a fix available** | **0** |
| Status breakdown | 21 `affected`, 13 `fix_deferred`, 1 `will_not_fix` |
| Where they are | Debian 12 base packages — `perl`, `util-linux`, `zlib1g` |
| In Fuseki, Jena or the JRE | **none** |

Check it yourself rather than believing the table:

```bash
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy \
  image --severity HIGH,CRITICAL ghcr.io/semantic-partners/sp-fuseki:6.2.0
```

**Why we don't fail the build on these.** Every one of them is unpatched *upstream*
— Debian has either deferred or declined a fix, so there is no version to move to.
A build that blocks on them is red on day one and every day after, with nothing a
maintainer could do about it. That is not a backlog, and a permanently red pipeline
teaches everyone to ignore it.

**What we do fail on: any finding that has a fix available.** Today that count is
zero, so the gate is green — and it goes red the day a patch exists that we haven't
picked up. That is the signal worth having, and it's the one thing in this area a
build can honestly assert.

The full report, unfixable findings included, is kept as a build artefact on every
publish, and goes to GitHub code scanning once this repo is public.

**The honest trade.** Those CVEs are in base packages this image never executes —
we need `ca-certificates` and `curl` for TLS and the healthcheck, not `perl` or
`libblkid`. A smaller base (distroless, or a JRE-only image) would cut most of the
surface. We haven't done it: it changes the debugging story inside the container,
and every one of these findings is currently unreachable in normal operation. If
your risk model says otherwise, that's a legitimate reason to build your own from
[image/Dockerfile](image/Dockerfile) — it's a short file, which is the point.

## Build & test locally

```bash
git config core.hooksPath hooks                # once: regenerate + test on commit
bash test/unit.sh                              # renderer + CI contract, sub-second, no Docker
docker build -f image/Dockerfile -t sp-fuseki:dev .
IMAGE=sp-fuseki:dev bash test/smoke.sh         # packaging contract, needs Docker
```

Two layers on purpose. [test/render_test.clj](test/render_test.clj) pins what the
EDN renders to and every way it refuses — fast enough to run on every edit.
[test/smoke.sh](test/smoke.sh) proves the container actually behaves that way.

### The CI workflow is generated, and tested

[.github/workflows/build.yml](.github/workflows/build.yml) is **generated** from
[ci/sp_fuseki/workflows.clj](ci/sp_fuseki/workflows.clj) — don't edit it. The
pre-commit hook regenerates and stages it; CI re-checks with
`bb ci/generate.clj --check` for anyone without the hook.

Actions YAML is a programming language with no compiler and, worse, nothing to
test. Generating it from data means the CI definition gets the same treatment as
everything else here — [test/workflows_test.clj](test/workflows_test.clj) asserts
that `latest` is only applied to the default Jena leg on `main`, that publishing
is never enabled for fork PRs, that arm64 jobs target the self-hosted labels
(a runner's *name* is not a label, and a wrong label **hangs** rather than
failing), and that `fromJSON` gets valid JSON. The generator refuses to emit a
workflow that uses an unavailable expression context, references an undeclared
matrix key, needs a job that doesn't exist, or runs multi-command shell without
`set -euo pipefail`.

The prose explaining *why* each job looks the way it does lives in the Clojure,
since YAML generators drop comments — that reasoning was worth more than the
formatting it used to live in.

The smoke test asserts the **packaging contract** — non-root, boot, `/$/ping`, a
POST→query round-trip, a mounted config honoured (not merged), Fuseki's UI served
with its bundle intact, the mutating admin API fenced under `anon`,
`FUSEKI_UI=off` serving no UI while data endpoints keep working, TDB2 on a
volume at `/fuseki/databases` surviving a restart, and the EDN path — rendering,
real RDFS entailment, TTL-beats-EDN precedence, and a malformed EDN failing loudly
at boot. It does **not**
test Jena's correctness; that's Apache's job (see
[docs/ASSESSMENT.md](docs/ASSESSMENT.md) §6).

## Publishing

CI ([.github/workflows/build.yml](.github/workflows/build.yml)) builds a **matrix
of Jena versions** multi-arch, smoke-tests each, pushes to GHCR with two-axis tags
(`<jena>-<sp-build>`, `<jena>`, and `latest` on the default leg only), generates an
SBOM, scans (Trivy — see *Vulnerability posture* above for what that blocks and
what it merely reports), and signs (cosign keyless). The default leg is whatever
`image/Dockerfile` pins; additional older versions can be published alongside it by
listing them in `EXTRA_JENA`, which is currently **empty**.

We published 6.1.0 briefly and stopped: the scan gate found two HIGH findings with
fixes available in that image's bundled jars — `shiro-core` 2.1.0
([CVE-2026-49268](https://avd.aquasec.com/nvd/cve-2026-49268)) and `jetty-security`
12.1.8 ([CVE-2026-10050](https://avd.aquasec.com/nvd/cve-2026-10050)), both patched
in what Jena 6.2.0 ships. An older leg is only worth publishing if it can be kept
patched, and that one can't be without Apache re-releasing it. So there is one
supported Jena at a time, and it is the current one.

Every artifact the build fetches — the Temurin JRE, the Fuseki tarball, babashka —
is verified against the hash its own publisher ships (`sha256`/`sha512`), because a
connection reset mid-transfer produces a truncated archive that can still unpack and
boot. That's integrity, not provenance: the checksum travels the same channel as the
artifact. [test/dockerfile_test.clj](test/dockerfile_test.clj) fails if a future
artifact is added without one.

There is no cron — builds run on bumps. Renovate watches Jena via
Maven Central, and [upstream-check.yml](.github/workflows/upstream-check.yml) runs
weekly against `archive.apache.org` and opens an issue if a newer Fuseki exists.
**Private first**;
flip the package public when the are-we-happy gate is met — see the RFC's
Distribution & CI section.

## Docs

- [docs/RFC.md](docs/RFC.md) — design, bets, milestones.
- [docs/ASSESSMENT.md](docs/ASSESSMENT.md) — gaps/weakpoints review.

## License

[Apache-2.0](LICENSE).
