# SP Fuseki — clean, data-driven Apache Jena Fuseki images

**Status:** draft RFC / starter README. Lift into a new repo
(`semantic-partners/fuseki` or `…/sp-fuseki`) and iterate.

A small suite of well-maintained, **config-respecting**, **data-driven** Apache
Jena Fuseki Docker images — the thing we keep reaching for and not finding.

---

## Why

There is no maintained, ergonomic Fuseki image. Each option fails differently:

- **`stain/jena-fuseki`** — what everyone uses; effectively unmaintained, tags
  stop at `5.1.0`. You hit "oh, but it doesn't have X" and there's no recourse.
- **`apache/jena-fuseki`** (official) — bare, opinion-free, awkward config story.
- **`secoresearch/fuseki`** — *regenerates* its config from env and **ignores a
  mounted `config.ttl`** (we hit this directly setting up the training lab).

So spinning up a triplestore — for a client engagement, a demo, a lab, CI — is a
papercut every time. This is felt **inside SP** on every project, and the gap is
real **outside** too (the flagship OSS triplestore genuinely lacks a good Docker
story). A clean image is low effort, high leverage, and on-brand.

## Goals

- **Config-respecting:** you mount config, we honour it. Never silently
  regenerate or override.
- **Data-driven:** the whole server described by **one data file** — datasets,
  reasoners, prefixes, federation, auth — rendered into Fuseki's assembler config
  at boot. Extension points as *data*, not a plugin SDK. (This is the
  differentiator, and it's pure SP "it's just data".)
- **Boring and reliable:** multi-arch, pinned Jena, non-root, healthcheck, slim,
  reproducible. Auto-bumped, CI-gated, so it stays green without heroics.
- **Dogfooded:** the training-lab devcontainer is adopter #1.

## Non-goals (v1)

- **The SPARQL UI.** A clean CodeMirror-6 query UI ("YASGUI without the plugin
  hell") is a *separate product* — weeks of frontend + ongoing maintenance. Do
  not couple it to the image. Decide it on its own merits later (Bet B). v1 ships
  the image only; leave a `:ui {:enabled …}` seam in the config for it.
- Replacing Fuseki, bundling a custom reasoner, or anything that forks Jena.

## Design (Bet A — the image)

- **Base / build:** build from the pinned Apache Jena dist (from
  `archive.apache.org`, permanent — not `dlcdn`, which is latest-only and 404s a
  pinned version once a newer Jena ships). Multi-arch via `buildx`
  (amd64 + arm64).
- **Entrypoint = babashka.** Fast startup, and EDN is the natural home for
  "config as data." The entrypoint reads `fuseki.edn` (or TTL), renders the
  Fuseki assembler `config.ttl` + `shiro.ini`, then execs `fuseki-server`. Fuseki
  itself stays the JVM; bb just orchestrates boot + config rendering.
- **Defaults:** non-root user; healthcheck on `/$/ping`; auth `:anon` for
  throwaway, `:basic` opt-in; in-memory or TDB2 per dataset.
- **Variants (tags):** `minimal` (server only) and later a `lab` variant with
  extras. Tags track the Jena version + variant; immutable version tags + a
  moving `latest`.

### The config, as data (the heart of it)

```clojure
;; fuseki.edn — the whole server described as data.
;; The bb entrypoint renders Fuseki's assembler config + shiro from this.
{:server   {:port 3030 :base "/fuseki"}
 :auth     {:mode :anon}                ; :anon | :basic
 :prefixes {:ex  "http://example.org/"
            :geo "http://geo.org/"}
 :datasets [{:name "training"
             :storage :mem               ; :mem | :tdb2
             :endpoints #{:query :update :gsp-rw}}
            {:name "training-inferred"
             :storage :mem
             :reasoner :none             ; :none | :rdfs | :owl-micro | …
             :endpoints #{:query :gsp-rw}}]
 :federation [{:name "dbpedia" :url "https://dbpedia.org/sparql"}]
 :ui {:enabled false}}                   ; Bet B seam — off in v1
```

One file, declarative, diffable, version-controlled. Add a dataset → add a map.
No bespoke plugin API to learn; the extension points *are* the data.

## Distribution & CI

- **Registry:** GHCR — `ghcr.io/semantic-partners/fuseki`. Free for public,
  trivial to push from Actions. (Mirror to Docker Hub later for discoverability.)
- **CI (GitHub Actions):** matrix `buildx` build + push; **smoke test** on each
  build — boot the image with a sample `fuseki.edn`, POST turtle to
  `…/data?default`, query it back, assert. (This harness already exists in spirit
  — it's what verified the course lab.)
- **Bumps:** Renovate/Dependabot watches Jena releases → opens a bump PR → CI
  re-tests → merge. Near-zero ongoing effort.

## Maintenance model & risk

- **The risk:** an *abandoned* "SP official image" is reputationally worse than
  none. The mitigation is automation — the image only earns the SP name if CI
  keeps it green without manual heroics. Bet A is built to clear that bar; Bet B
  (the UI) is not, easily — another reason to keep them apart.
- **Licensing:** Apache Jena is Apache-2.0; repackaging is fine. Name it clearly
  as *SP's distribution* — don't imply Apache endorsement. Repo under Apache-2.0
  or MIT.

## Milestones

- **v0.1** — minimal image, bb entrypoint + `fuseki.edn` rendering, non-root,
  healthcheck, multi-arch, GHCR publish, CI smoke test, README. (~3–5 focused
  days.)
- **v0.2** — `tdb2` storage, `:basic` auth, reasoner options, `lab` variant,
  docs/examples, Renovate wired.
- **later** — Bet B (UI) as its own project, slotting into the `:ui` seam.

## First adopter (dogfood)

The training-lab devcontainer currently pins `stain/jena-fuseki:5.1.0`. Once v0.1
ships, point it at `ghcr.io/semantic-partners/fuseki:5.1.0` — immediate internal
consumer, and the course demonstrates SP's own tooling.

### lance comments
we fixed a crud bug on jena that's coming in 6.2.0 
writes left a corrupted parquet. 


## Open decisions

- Config format: **EDN** (Clojure-native, great with bb) vs **TTL** (already RDF,
  closer to Fuseki's own assembler). Lean EDN for the entrypoint; could accept
  either.
- Build base: from-dist (full control) vs extend `apache/jena-fuseki` (less to
  own). Lean from-dist for the config-respecting guarantee.
- Naming: `sp-fuseki` vs `fuseki` under the org; image tag scheme.
- Bet B: build greenfield vs embed/improve an existing UI.
