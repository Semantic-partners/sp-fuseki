# sp-fuseki

Clean, **config-respecting**, **legible** Apache Jena Fuseki Docker images.

You mount config, we honour it. The boot path is a short, readable babashka
script — not five layers of entrypoint bash — and **every extension point is
documented below and exercised by the smoke test**. That tested legibility is
the point: you can understand and trust exactly what the container does.

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
| Assembler config | `/fuseki/config.ttl` | If present, **honoured untouched**. If absent, a minimal in-memory dataset is generated. Never merged or silently regenerated. |
| Shiro auth config | `/fuseki/shiro.ini` | If present, **honoured untouched** (your escape hatch for any auth setup, incl. SOPS-decrypted secrets). If absent, generated from `FUSEKI_AUTH`. |
| **Persistent data** | `/fuseki/databases` | **Mount your volume here** for TDB2 datasets. Pre-created in the image and owned by uid 1000 — see below. |

The **effective** config and shiro that actually run are always written to
`$FUSEKI_BASE` (`config.effective.ttl`, `shiro.ini`) and their paths logged at
boot — so "what did it actually run" is never a mystery.

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
| `FUSEKI_SHIRO` | `/fuseki/shiro.ini` | Where to look for a mounted shiro.ini. |
| `FUSEKI_PORT` | `3030` | Listen port. |
| `FUSEKI_DATASET` | `ds` | Name of the generated default dataset (when no config mounted). |
| `FUSEKI_AUTH` | `anon` | `anon` (throwaway/lab) or `basic` (all endpoints require login, except `/$/ping` so the healthcheck works). |
| `FUSEKI_UI` | `on` | `on` serves Fuseki's own UI + admin area at `/`. `off` runs the headless server — no UI, no admin area, data endpoints unchanged. Same image either way. |
| `FUSEKI_ADMIN_USER` | `admin` | Basic-auth username. |
| `FUSEKI_ADMIN_PASSWORD` | — | Basic-auth secret, inline. |
| `FUSEKI_ADMIN_PASSWORD_FILE` | — | Basic-auth secret, read from a file (Docker/K8s secret, vault-agent sink, SOPS output). Preferred over the inline form. |

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

## Build & test locally

```bash
docker build -f image/Dockerfile -t sp-fuseki:dev .
IMAGE=sp-fuseki:dev bash test/smoke.sh
```

The smoke test asserts the **packaging contract** — non-root, boot, `/$/ping`, a
POST→query round-trip, a mounted config honoured (not merged), Fuseki's UI served
with its bundle intact, the mutating admin API fenced under `anon`,
`FUSEKI_UI=off` serving no UI while data endpoints keep working, and TDB2 on a
volume at `/fuseki/databases` surviving a restart. It does **not**
test Jena's correctness; that's Apache's job (see
[docs/ASSESSMENT.md](docs/ASSESSMENT.md) §6).

## Publishing

CI ([.github/workflows/build.yml](.github/workflows/build.yml)) builds a **matrix
of Jena versions** multi-arch, smoke-tests each, pushes to GHCR with two-axis tags
(`<jena>-<sp-build>`, `<jena>`, and `latest` on the default leg only), generates an
SBOM, scans (Trivy), and signs (cosign keyless). The default leg is whatever
`image/Dockerfile` pins; older versions we still publish are listed in
`EXTRA_JENA`. There is no cron — builds run on bumps. Renovate watches Jena via
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
