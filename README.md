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
add its assertion to [test/smoke.clj](test/smoke.clj) — that's what stops the
README drifting away from what the container actually does.

> **Status: v0.1, published to GHCR.** One image, both postures: **Fuseki's own
> UI ships enabled** (asserted by the smoke test), and `FUSEKI_UI=off` gives you
> the headless server. No separate `minimal`/`full` tags — it's a runtime switch,
> not a build variant. A bespoke SPARQL editor UI is a later, separate decision.

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

### The entrypoint — don't replace it

```
ENTRYPOINT ["bb", "/opt/sp-fuseki/entrypoint.clj"]     # and no CMD
```

**You do not need to set an `ENTRYPOINT`, and you should not.** It is what resolves
your config (mounted `config.ttl`, else `fuseki.edn`, else a generated default),
renders `shiro.ini`, writes the effective config and the resolved port where the
healthcheck reads it, and then execs Fuseki. Override it and you get a Fuseki with
none of the contract on this page — the mounts and env vars below stop doing
anything, silently.

**You do not need `tini` or `--init` either.** The entrypoint `exec`s, so the JVM
*replaces* it and becomes PID 1 directly:

```console
$ docker exec <container> cat /proc/1/cmdline | tr '\0' ' '
java -jar /opt/fuseki/fuseki-server.jar --port=3030 --config=/fuseki/run/config.effective.ttl
```

There is no shell left to leak zombies or swallow signals, and the JVM handles
`SIGTERM` itself — `docker stop` returns immediately rather than waiting out the
10-second kill timeout. If you are porting from an image whose Dockerfile said
`ENTRYPOINT ["/sbin/tini", "--", "/entrypoint.sh"]`, drop both halves: the init
shim and the shell script.

**The image takes no arguments, and says so.** There is no `CMD`, so anything you
put in `command:` (Compose) or `CMD` (a derived image) arrives as argv to the
entrypoint — where it reaches neither Fuseki nor a shell. It used to be ignored
silently, which meant a lock check or a migration wired up that way never ran and
never said so. Now it stops the boot and points at the two things that do work:

```console
$ docker run --rm ghcr.io/semantic-partners/sp-fuseki /my/setup.sh
[sp-fuseki] FATAL: this image takes no arguments, and got: /my/setup.sh
  Nothing you pass as CMD or `command:` reaches Fuseki — ENTRYPOINT resolves
  the config and execs the server itself.
  To run something before Fuseki starts: mount it executable into
    /fuseki/pre-start.d/10-yours.sh   (or set FUSEKI_PRESTART)
  To replace the boot entirely: a wrapper ENTRYPOINT whose last line execs
    bb /opt/sp-fuseki/entrypoint.clj
  To poke around: docker run --rm -it --entrypoint sh <image>
```

**If you need work done before Fuseki starts**, mount a hook — see below. If you
genuinely must replace the boot (running as root, say), your wrapper has to `exec`
ours as its last line, or you inherit PID 1 and the signal handling that comes with
it.

For poking around, `--entrypoint` is still fine and changes nothing permanent:

```bash
docker run --rm -it --entrypoint sh ghcr.io/semantic-partners/sp-fuseki
```

### The healthcheck — don't redeclare it either

The image ships a `HEALTHCHECK`, and Compose inherits it. **Write no `healthcheck:`
stanza** — see [examples/docker-compose.yml](examples/docker-compose.yml).

The reason matters more than the convention. Ours reads the **resolved** port:

```sh
p=$(cat "${FUSEKI_BASE:-/fuseki/run}/port" 2>/dev/null || echo "${FUSEKI_PORT:-3030}")
curl -fsS "http://localhost:${p}/$/ping" || exit 1
```

so it still works when the port comes from `fuseki.edn`'s `:server {:port n}` or
`FUSEKI_PORT`. A hardcoded `http://localhost:3030/...` in your Compose file serves
fine and reports **unhealthy** the moment anyone changes the port.

If you only need different timings, set them and omit `test` — Compose keeps the
image's probe and overrides the schedule:

```yaml
healthcheck:
  interval: 5s
  timeout: 2s
  retries: 3
  start_period: 20s
```

If you do write your own `test`: the image has **`curl`** and **not `wget`** (Debian
base, not Alpine), and `$` needs no escaping in a Compose `CMD-SHELL` string —
`docker compose config` echoes it back as `$$`, which is Compose quoting its own
output, not a change to the value.

### Mounts

| Mount | Default path | Behaviour |
|---|---|---|
| Assembler config | `/fuseki/config.ttl` | If present, **honoured untouched**. Wins over `fuseki.edn`. Never merged or silently regenerated. |
| EDN config | `/fuseki/fuseki.edn` | If present (and no `config.ttl`), validated and **rendered** to assembler TTL — see below. |
| Shiro auth config | `/fuseki/shiro.ini` | If present, **honoured untouched** (your escape hatch for any auth setup, incl. SOPS-decrypted secrets). If absent, generated from `FUSEKI_AUTH`. |
| **Persistent data** | `/fuseki/databases` | **Mount your volume here** for TDB2 datasets. Pre-created in the image and owned by uid 1000 — see below. |
| Pre-start hooks | `/fuseki/pre-start.d` | Executable scripts run in filename order **before** the config is resolved — see below. Optional; absent is the normal case. |

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
| `:datasets` | `:name`, `:storage` (`:mem`/`:tdb2`), `:endpoints` (see below), `:reasoner` (`:none`/`:rdfs`/`:owl-micro`) |
| `:prefixes` | keyword → IRI, declared once and emitted into the TTL |
| `:auth` | `{:mode :anon}` or `{:mode :basic}` |
| `:server` | `{:port 3030}` — honoured, and the container's healthcheck follows it |
| `:ui` | `{:enabled true}` |
| `#env "VAR"` / `#file "path"` | read a secret at boot — it never lives in the config |
| `#include "path"` | splice in another EDN file, resolved relative to the file that wrote it |
| `:text` | a Lucene full-text index over chosen predicates — see below |

### Full-text search — `:text`

`FILTER regex(?label, …)` is unusable at any real size. `:text` builds a Lucene
index over the predicates you name, so you can `?s text:query "chinook"`:

```clojure
{:prefixes {:skos "http://www.w3.org/2004/02/skos/core#"
            :rdfs "http://www.w3.org/2000/01/rdf-schema#"}
 :datasets [{:name "kb" :storage :tdb2 :endpoints #{:query}
             :text {:default-field :label
                    :store-values  true
                    :fields {:label     :rdfs/label
                             :prefLabel :skos/prefLabel}}}]}
```

```sparql
PREFIX text: <http://jena.apache.org/text#>
SELECT ?s WHERE { ?s text:query "chinook" }              # the default field
SELECT ?s WHERE { ?s text:query (skos:prefLabel "chinook") }   # one named field
```

| Key | |
|---|---|
| `:fields` | **required** — field name → predicate. A namespaced keyword (`:rdfs/label`) uses your `:prefixes`; a string is a full IRI. |
| `:default-field` | which field a bare `text:query` searches. Defaults to the first alphabetically — name it if that matters. |
| `:directory` | index location. Defaults to `<tdb2 root>/<dataset>-lucene`, so it lands on the writable mount for you. `"mem"` for an in-memory index. |
| `:analyzer` | `:standard` (default), `:keyword`, `:simple`, `:lower-case-keyword` |
| `:store-values` | keep the literal in the index as well as the term |

**A text index *wraps* a dataset rather than being a property of one** — the
rendered TTL is a `text:TextDataset` containing your real store plus the index.
That's why `:text` sits beside `:storage` rather than inside it, and why
`:reasoner` with `:text` is refused: whether the index should see entailed
triples is a decision we haven't made, and guessing would be a config that lies.

The generated index and entity map are **named** resources (`<#kb-entitymap>`),
not blank nodes. That isn't style: jena-text reads the entity map by IRI, and a
blank node arrives as null and takes the whole config down with a
`NullPointerException` naming nothing you wrote.

> **`:text` is the one key backed by a Jena *module* rather than core assembler.**
> Everything else denotes vocabulary that is present by definition; `jena-text`
> could be absent from a differently-built image. So the entrypoint checks the
> jar at boot and refuses in terms of the key you wrote. Without that check the
> failure is Jena's `NoSpecificTypeException` naming a node in a *generated* file
> and explaining itself via `ja:Object` subclassing — accurate, and no path back
> to your `fuseki.edn`.
>
> This narrows the promise, and the narrowing is deliberate: "refuses to boot
> rather than half-configuring" becomes "refuses to boot for the things we can
> see". A module we don't know to probe for is a gap, not a guarantee.

Parameterised analyzers (localized, configurable, generic) are refused by name
rather than half-supported — they're a configuration of their own. Mount a
`config.ttl` for those.

### Endpoints — what path a dataset answers on

`:endpoints` takes either a set of operations, which get **Fuseki's conventional
names**, or a map, which lets you name them yourself:

```clojure
:endpoints #{:query :update}          ; -> /ds/sparql  /ds/update
:endpoints {:query   ["sparql" "query" ""]  ; -> /ds/sparql  /ds/query  /ds
            :update  true                   ; -> /ds/update (the conventional name)
            :gsp-rw  nil}                   ; -> /ds  (graph store at the root)
```

| Value | Means |
|---|---|
| `true` | Fuseki's conventional name for that operation |
| `"foo"` | `/ds/foo` |
| `nil` or `""` | the **dataset root**, `/ds` — an endpoint with no `fuseki:name` |
| a vector | several of the above, in the order written |

**`:query` is `sparql`, not `query`.** That's Fuseki's convention, not ours —
stock Fuseki serves a query endpoint at `/ds/sparql`, so `/ds/query` is a 404
unless you ask for it. It surprises people, so **the resolved routes are logged
at boot**:

```
[sp-fuseki] routes: ds -> gsp-rw /ds/data | query /ds/sparql | update /ds/update
```

Grouped by operation, because the path alone half-answers the question the line
exists for: `routes: x -> /x` is true and useless when `/x` serves query, update
*and* gsp-rw at once.

A route is a decision like `:auth` or `:port`, and this is the same rule those
follow — what took effect is stated, not left to be discovered by a 404.

**The defaults are Fuseki's defaults, deliberately.** Serving `/ds/query` as well
would remove the papercut, and it was rejected: it fixes it by shipping an alias
Fuseki doesn't have. This is a notation *for* the assembler, not an improved
Fuseki — the moment our defaults are better than Fuseki's, what you learn here
stops transferring to a config written by hand. So the default matches, and the
override above is how you get any path you want.

Two operations can share the root (Fuseki dispatches those on the request, which
is what a bare `fuseki:serviceQuery ""` relies on), but a **named** path claimed
by two operations is refused — one path can only mean one thing.

### Splitting a config up — `#include`

A config with several datasets stops being readable as one file:

```clojure
;; /conf/fuseki.edn
{:datasets [#include "sets/chinook.edn"
            #include "sets/offshore.edn"]}
```

Relative paths resolve against **the file that wrote them**, not the working
directory, so a config directory works wherever you mount it. Absolute paths work
too. Cycles are caught and reported with the trail (`a.edn -> b.edn -> a.edn`),
and a missing include is a `FATAL` at boot naming the path it resolved to.

The tag set — `#env`, `#file`, `#include` — is **closed and complete**. That is
deliberate: taking a config library off the shelf would bring its whole tag
vocabulary along, and every tag we didn't document would be an extension point
that works but isn't stated, which is the property this image exists to not have.

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

**`/fuseki/run/configuration` is pre-created too, for the same ownership reason.**
It's a path Fuseki's own docs send you to, and mounting a single file into it
(`-v ./ds.ttl:/fuseki/run/configuration/ds.ttl`) makes Docker create the *missing
parent* root-owned — Fuseki then dies `Not writable` before serving anything.
Creating it in the image costs an empty directory and removes the trap.

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
| `FUSEKI_PRESTART` | `/fuseki/pre-start.d` | Directory of pre-start hooks. Set it and the directory must exist — a path you asked for and we cannot find is fatal. |
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

### Pre-start hooks — work that must happen before Fuseki

Mount executable scripts into `/fuseki/pre-start.d` and they run in filename order,
as uid 1000, before anything is resolved:

```yaml
services:
  fuseki:
    image: ghcr.io/semantic-partners/sp-fuseki:6.2.0
    volumes:
      - ./hooks:/fuseki/pre-start.d:ro     # 10-fetch-secret.sh, 20-restore.sh, …
      - ./data:/fuseki/databases
```

**Before anything is resolved** is the useful part: a hook can write the very
`fuseki.edn` the boot then reads. Fetch a secret, template a config from a service
discovery lookup, restore a backup into `/fuseki/databases` — all of it produces
input the boot consumes, so it has to come first.

Their output goes to the container log, between the environment warnings and the
config lines, and each one is named as it runs:

```
[sp-fuseki] pre-start: running /fuseki/pre-start.d/10-restore.sh
restoring 4.2 GB from s3://…
[sp-fuseki] pre-start: /fuseki/pre-start.d/10-restore.sh ok
[sp-fuseki] effective config -> /fuseki/run/config.effective.ttl (rendered from /fuseki/fuseki.edn)
```

**Hooks fail closed.** A non-zero exit stops the boot, naming the script and the
code; later hooks do not run. A hook that failed means a precondition did not hold,
and starting anyway is how "seed the database" becomes a live empty server.

**A hook that is not executable is also fatal**, with the `chmod +x` you need. That
bit goes missing easily — a bind mount from a filesystem without it, a checkout on
Windows — and warning-and-skipping would hand you the silent no-op this seam exists
to replace.

Dotfiles and subdirectories are ignored (and listed in the log, so nothing
disappears quietly). That is deliberate: Kubernetes builds ConfigMap and Secret
volumes out of `..data` symlinks and timestamped staging directories, and refusing
those would make the feature unusable exactly where people mount things from.

| Situation | What happens |
|---|---|
| No `/fuseki/pre-start.d`, or an empty one | Nothing, silently. The overwhelmingly common case. |
| `FUSEKI_PRESTART` set to a path that is not there | **Fatal.** A path you asked for and we cannot find is an instruction we could not honour. |
| Hook exits non-zero | **Fatal**, naming the script and its exit code. Later hooks skipped. |
| Hook is not executable | **Fatal**, naming the file and the fix. Nothing runs. |
| Hook needs root | Not this seam — use a wrapper `ENTRYPOINT`, and `exec` ours at the end. |

> **Do you actually need one?** If the hook is a TDB2 lock check, probably not: Jena
> takes an OS lock on `tdb.lock`, the kernel releases it when the process dies, and
> a crash-restart on the same volume boots clean. Two containers opening one volume
> at once fail on the second, which is the outcome you want — and *deleting* the
> lock file to "fix" that turns a refusal into two live writers on one database.

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

**Every published image carries an SBOM**, so "is my tool in there?" is answerable
without pulling it — 199 packages on the 6.2.0 image:

```bash
docker buildx imagetools inspect ghcr.io/semantic-partners/sp-fuseki:6.2.0 \
  --format '{{json .SBOM}}' | jq -r '.["linux/amd64"].SPDX.packages[].name' | sort
```

(SPDX is just a standard JSON inventory format; syft writes it and buildx attaches
it at publish. You don't need to know the format to grep the list.) That's how you
confirm `curl` is present and `wget` isn't, rather than starting a shell to find
out. Provenance attestations are attached the same way.

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

**Supply chain.** Separate question from the CVE table, and the honest summary is
that the three fetched artifacts are not equally protected. All three are
checksum-verified; Fuseki and the Temurin JRE additionally have their PGP
signature checked against a **pinned key fingerprint**; babashka publishes no
signature at all. Full detail under [Publishing](#publishing).

One asymmetry worth stating here rather than leaving in the Dockerfile: the JRE's
public key comes from a keyserver, so key and artifact travel different channels.
Apache serves `KEYS` from the same host as the tarball, so anyone who owns that
host owns both — and **the pinned fingerprint is the only thing standing there**.
It is doing more work in the Fuseki case than in the JRE one.

## Build & test locally

```bash
git config core.hooksPath hooks                # once: regenerate + test on commit
bash test/unit.sh                              # renderer + CI contract, sub-second, no Docker
docker build -f image/Dockerfile -t sp-fuseki:dev .
IMAGE=sp-fuseki:dev bash test/smoke.sh         # packaging contract, needs Docker
```

Two layers on purpose. [test/render_test.clj](test/render_test.clj) pins what the
EDN renders to and every way it refuses — fast enough to run on every edit.
[test/smoke.clj](test/smoke.clj) proves the container actually behaves that way.

Both layers are babashka. The smoke suite used to be 531 lines of bash and produced
four defects in a day, none of them logic errors: `docker logs | grep -q` SIGPIPEd
docker so `pipefail` failed a *successful* assertion; `$(printf '\n')` lost its
newline so a negative test sent the valid credential; `grep -qv` is vacuously true,
so a check that a secret wasn't logged would have passed while leaking it. HTTP
status codes are now integers rather than `-w '%{http_code}'` output, and a string
containing a newline is just a string. `test/smoke.sh` remains as the entry point,
and finds `bb` on PATH, in the usual install locations, or — on Linux — copies the
checksum-verified one out of the image itself, so no extra download is involved.

### The CI workflow is generated, and tested

[.github/workflows/build.yml](.github/workflows/build.yml) is **generated** from
[ci/sp_fuseki/workflows.clj](ci/sp_fuseki/workflows.clj) — don't edit it. The
pre-commit hook regenerates and stages it; CI re-checks with
`bb ci/generate.clj --check` for anyone without the hook.

Actions YAML is a programming language with no compiler and, worse, nothing to
test. Generating it from data means the CI definition gets the same treatment as
everything else here — [test/workflows_test.clj](test/workflows_test.clj) asserts
that `latest` is only applied to the default Jena leg on `main`, that publishing
is never enabled for fork PRs, and that no job can be scheduled onto a
self-hosted runner (both arches are GitHub's — and a wrong runner label **hangs**
rather than failing, so it is asserted rather than eyeballed). The generator refuses to emit a
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
volume at `/fuseki/databases` surviving a restart, a file mounted into
`/fuseki/run/configuration` not killing the boot, and the EDN path — rendering,
real RDFS entailment, TTL-beats-EDN precedence, named and root endpoints
answering, `#include` splicing a config directory, a Lucene index returning real
hits on every mapped field, the module probe refusing a `:text` config when the
jar lacks `jena-text`, and both a malformed EDN and a missing include failing
loudly at boot. It does **not**
test Jena's correctness; that's Apache's job.

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
boot. [test/dockerfile_test.clj](test/dockerfile_test.clj) fails if a future artifact
is added without one.

**A checksum is integrity, not provenance** — it travels the same channel as the
artifact, so anyone who can alter one can alter the other. Two of the three
artifacts now carry the stronger claim: their PGP signature is verified **against a
pinned key fingerprint**.

| Artifact | Integrity | Provenance | Signer |
|---|---|---|---|
| Fuseki | `sha512` | `.asc`, pinned | `D99038A1…` — Andy Seaborne, Apache code-signing key |
| Temurin JRE | `sha256` | `.sig`, pinned | `3B04D753…` — Adoptium, key from a keyserver |
| babashka | `sha256` | **none available** | publishes neither `.sig` nor `.asc` |

**The pin is the whole point.** `gpg --verify` exits `0` for a good signature from
*any* key in its ring, so importing a key file and trusting the exit status would
pass for an artifact signed by anybody who could also serve you that key file. The
build reads gpg's machine-readable status and requires `VALIDSIG <pinned fingerprint>`:

```
provenance: JRE signature verified against pinned key 3B04D753C9050D9A5D343F39843C48A565F8F04B
provenance: signature verified against pinned key D99038A1731B8B31B71549EF04C95136D236A58F
```

**A release cut by a different signer will fail the build**, deliberately —
trusting a new key should be a person's decision, not a silent consequence of a
version bump. The failure prints the signature's actual `VALIDSIG` line, so the
fingerprint to consider is already in front of you. Update `JENA_KEY_FPR` /
`TEMURIN_KEY_FPR` in the same commit as the version.

The JRE's public key is fetched from a **keyserver** rather than from the release
host, which is strictly better than the Fuseki case: key and artifact then travel
different channels, so compromising the download host isn't enough. Apache
publishes `KEYS` on its own infrastructure, so there the pin is doing all the work.

babashka's gap is real and checked rather than assumed — `.sig` and `.asc` both
404 against its release assets, and `dockerfile_test.clj` asserts it stays
checksum-only rather than quietly appearing verified.

There is no cron — builds run on bumps. Renovate watches Jena via
Maven Central, and [upstream-check.yml](.github/workflows/upstream-check.yml) runs
weekly against `archive.apache.org` and opens an issue if a newer Fuseki exists.
**Private first**;
flip the package public when the are-we-happy gate is met — see the RFC's
Distribution & CI section.

## Docs

- [docs/ADR-001-config-authority.md](docs/ADR-001-config-authority.md) — what the
  EDN gates, what it passes through, and why it's a notation rather than a format.

## License

[Apache-2.0](LICENSE).
