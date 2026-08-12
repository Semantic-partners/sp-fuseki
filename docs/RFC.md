# SP Fuseki — clean, legible Apache Jena Fuseki images

**Status:** draft RFC, living in the repo. This revision folds in the
conclusions of [ASSESSMENT.md](ASSESSMENT.md) — the differentiator is restated
(legibility, not EDN), the config-format lean is flipped (TTL-first), and the UI
is reopened as tiers. Where this RFC and the assessment ever drift, the
assessment is the newer thinking.

A small suite of well-maintained, **config-respecting**, **legible** Apache Jena
Fuseki Docker images — the thing we keep reaching for and not finding.

---

## Why

There is no maintained, ergonomic Fuseki image. Each option fails differently:

- **`stain/jena-fuseki`** — what everyone uses; effectively unmaintained, tags
  stop at `5.1.0`. You hit "oh, but it doesn't have X" and there's no recourse.
- **`apache/jena-fuseki`** (official) — bare, opinion-free, awkward config story.
- **`secoresearch/fuseki`** — *regenerates* its config from env and **ignores a
  mounted `config.ttl`** (we hit this directly setting up the training lab).

But the deeper papercut is shared by all three: **to learn how to configure or
extend them, you reverse-engineer five layers of entrypoint bash.** Which mount
does what? Which env var triggers what? Why did my mounted config get ignored?
The extension points are *emergent from the script*, never *stated*. So spinning
up a triplestore — for a client engagement, a demo, a lab, CI — is a papercut
every time. This is felt **inside SP** on every project, and the gap is real
**outside** too (the flagship OSS triplestore genuinely lacks a good Docker
story). A clean, legible image is low effort, high leverage, and on-brand.

## What we're offering (the thesis)

Not a new config language. Not just "well-operated" (copyable, thin moat). The
product is **the extension contract, documented and tested** — instead of
reverse-engineered from bash. Concretely, a user can read:

- what they can mount and exactly what each does (`config.ttl`, `shiro.ini`,
  data dir);
- every env var and its effect, including the secret convention;
- where the entrypoint writes the rendered/effective config, so it can be
  inspected;
- recipes for the common moves — add a dataset, switch to TDB2, enable a
  reasoner, turn on auth, enable the UI.

…and **every documented extension point is exercised by a smoke test**, so the
docs cannot drift from the entrypoint. That tested legibility is the moat:
people choose it because they can *understand and trust* it. This is pure SP
"it's just data" — the config stays RDF; the seams are explicit data, not magic.

## Goals

- **Config-respecting:** you mount config, we honour it. Never silently
  regenerate or override.
- **Legible & documented:** the extension points are stated and tested, not
  emergent from an entrypoint script. A short, readable boot path.
- **Boring and reliable:** multi-arch, pinned Jena, non-root, healthcheck, slim,
  reproducible. Auto-bumped, CI-gated, signed, so it stays green without heroics.
- **Dogfooded:** the training-lab devcontainer is adopter #1.

## Non-goals (v1)

- **A bespoke from-scratch SPARQL UI.** v1 ships the image; a fully custom query
  UI is decided separately (Bet B) — not because it's hard, but because it's
  *ongoing maintenance* and competes for scarce time. This does **not** mean "no
  UI": keeping Fuseki's own webapp, or bundling/reskinning YASGUI, are cheap
  image features — see *The UI, as tiers* below.
- Replacing Fuseki, bundling a custom reasoner, or anything that forks Jena.

## Design (Bet A — the image)

- **Base / build:** build from the pinned Apache Jena dist (from
  `archive.apache.org`, permanent — not `dlcdn`, which is latest-only and 404s a
  pinned version once a newer Jena ships). Multi-arch via `buildx`
  (amd64 + arm64). Target Jena **6.x** (the lab toolchain is already on 6.1.0;
  don't re-freeze a 5.x mismatch).
- **Entrypoint = babashka.** Not for startup speed (a JVM boots for Fuseki
  regardless — entrypoint time is noise). For **legibility**: a real language
  instead of bash to orchestrate boot — parse/validate config, render
  `shiro.ini`, healthcheck, exec — so the seams are documentable and the boot
  path is auditable. Bash accretion is exactly what made the incumbents
  un-documentable. The honest cost: bb is the one pinned, arch-specific,
  non-Java piece in the build.
- **Defaults:** non-root user; healthcheck on `/$/ping`; auth `:anon` for
  throwaway, `:basic` opt-in; in-memory or TDB2 per dataset.
- **Variants (tags):** ~~`minimal` (server only) and `full` (keeps Fuseki's own
  UI)~~ — **superseded in v0.1.** Both servers ship in the one `fuseki-server.jar`
  (its `Main-Class` is the UI+admin build; `FusekiServerPlainCmd` is headless), so
  this is a runtime switch, `FUSEKI_UI=on|off`, not a tag axis. A variant axis
  would have doubled every multi-arch build leg to select a different main class.
  Tags carry the Jena version + sp-build only; immutable version tags + a moving
  `latest`.

### Config: TTL-first, respected, inspectable

The config **is Fuseki's own assembler `config.ttl`** — already RDF, already
"just data," zero new standard to learn. v0.1 does not invent a config language;
it makes the existing one *legible and respected*. The dogfood config is two
in-memory datasets and a handful of endpoints — e.g.:

```turtle
@prefix fuseki: <http://jena.apache.org/fuseki#> .
@prefix ja:     <http://jena.hpl.hp.com/2005/11/Assembler#> .

:trainingService a fuseki:Service ;
    fuseki:name "training" ;
    fuseki:endpoint [ fuseki:operation fuseki:query  ; fuseki:name "sparql" ] ;
    fuseki:endpoint [ fuseki:operation fuseki:update ; fuseki:name "update" ] ;
    fuseki:endpoint [ fuseki:operation fuseki:gsp-rw ; fuseki:name "data" ] ;
    fuseki:dataset [ a ja:RDFDataset ; ja:defaultGraph [ a ja:MemoryModel ] ] .
```

**Config resolution (the contract):**

- a `config.ttl` mounted → passed through untouched (pure config-respecting);
- a `shiro.ini` mounted → passed through untouched;
- the entrypoint always writes the **effective** config to a known path and logs
  it, so "what did it actually run" is never a mystery;
- malformed config fails *loudly* at boot with a clear message — never boots
  half-configured.

A higher-level **EDN/aero convenience layer** (`fuseki.edn`, the whole server as
data) is attractive but is **deferred to v0.2** and decided on its own merits —
it would be the Nth config standard, and the dogfood needs none of it. When/if
added, it is a *generator over the assembler TTL* with the passthrough above as
the escape hatch, never a replacement. (See `examples/fuseki.edn` for the sketch.)

### Secrets

`:basic` auth needs credentials, and they never belong in a committed,
version-controlled config. Best practice churns (encrypted-in-repo / Vault / env
/ Docker secrets) and **the image must not pick a backend.** Credentials live in
`shiro.ini`, which the entrypoint renders regardless of config format — so this
is a *shiro-rendering* concern, format-agnostic. Two mechanisms cover every
school, because they all deliver to env or a file:

- **env / `*_FILE` convention** read at boot (`FUSEKI_ADMIN_PASSWORD` /
  `FUSEKI_ADMIN_PASSWORD_FILE`) — covers 12-factor, K8s, vault-agent, Docker
  secrets;
- **mount your own `shiro.ini`** (passthrough) — this *is* the diffable-secrets
  path: SOPS-decrypt at deploy and mount.

### The UI, as tiers

A UI is not one bet, and the RFC originally over-stated the cost. The tiers:

| Tier | What | Cost | Status |
|---|---|---|---|
| **1. Keep Fuseki's own UI** | Don't strip the UI Fuseki ships — `FUSEKI_UI=on`, the default (no `full` tag; see *Variants* above) | ~zero, and it was already there | **shipped in v0.1**, smoke-asserted |
| **2. Thin CodeMirror web component** | SPARQL editor + **results-as-data** (raw/tabular), built fresh on the CodeMirror config — framework-free, embeddable | ~the old YASGUI-reskin budget, but clean (no wrapper debt) | v0.2 variant |
| **3. Fuller bespoke UI** | tier 2 + prefixes / history; possibly a standalone app | more than tier 2; gate is *opportunity cost*, not build difficulty | Bet B, separate |

**Rejected: reskinning YASGUI.** Its only real value is the CodeMirror config;
the rest is the plugin-hell wrapper you end up fighting. Lift the CodeMirror
config directly and build a thin shell — skip the wrapper. **Web component, not
React:** zero framework runtime, portable, CSP-friendly static assets, drop-in
to the `full` variant or any page, reusable against any endpoint (on-brand "it's
just data"). React only earns its keep if tier 3 grows into a standalone app.

**No result-viz plugin suite.** YASGUI's other arguable value — canned result
views (table / geo / chart / pivot) — was worth shipping *pre-LLM*, when bespoke
viz was expensive. In an LLM world that flips: a good-enough visualisation is a
half-day for an agent, on demand, per use. So the UI ships query + results-as-
data and stops there; visualisation is generated against the API, not bundled.
**APIs are what matter** — the clean, documented endpoint is the asset; canned
views downstream of it are cheap now. (Held loosely — true *for now*.)

The real papercut is "spin one up, load data, and *poke at it*" — an API-only
image doesn't solve it; a batteries-included one does. stain stayed popular
*because* it's "Fuseki that just runs, with a window into your data." So a UI
option may be the differentiator, not scope creep. It is just another
**documented, auth-aware extension point** (anon + exposed update endpoint is a
footgun — the UI variant defaults auth-aware). Only tier 3 stays a separate
decision, and even there the question is "is it a *useful* couple of days given
everything else on," not "can we afford weeks."

## Distribution & CI

- **Registry — GHCR primary, Docker Hub mirror.** GHCR
  (`ghcr.io/semantic-partners/sp-fuseki`) is the canonical home: free, **no pull
  rate limits** on public images, native `GITHUB_TOKEN` push, first-class
  cosign/provenance. Docker Hub is *no longer* the right primary — anonymous
  pulls are throttled to **10/hour** (April 2025), crippling for a public image
  as a sole source — but it's still where people search and where a bare
  `docker pull` resolves, so mirror to it for discoverability. Build once, push
  to both in the same Actions job; the dogfood devcontainer pins the GHCR ref.
- **Rollout — private first, flip when happy.** GHCR package visibility is
  independent of repo visibility, so start the package **private** (the default
  on first push from a private repo) and dogfood internally — pulls just need a
  token with `read:packages` during this phase. When the "are we happy" gate is
  met (signing + SBOM + scan on, two-axis tags settled, smoke green), flip the
  package to public in one step — **no rebuild**, the same digests become
  world-pullable. Flip the *repo* to public separately (you can even keep source
  private and the image public). Pre-flip checks: the org must permit public
  package publishing, and the supply-chain bits must be on *before* public —
  that's when provenance matters.
- **Tag scheme — two-axis.** Do **not** tag by Jena version alone: the SP layer
  (entrypoint, renderer) has its own fixes that need version space. Use
  `<jena>-<sp-build>` + variant, e.g. `6.2.0-1`, `6.2.0-1-full`, plus moving
  `6.2.0` and `latest`. Decide before first publish — retagging after adopters
  pin is painful.
- **CI (GitHub Actions):** matrix `buildx` build + push; **smoke test** on each
  build scoped to the *packaging contract* — boot, declared endpoints respond
  (POST turtle → query back), mounted config honoured (not regenerated),
  non-root, healthcheck, both arches, and a TDB2 write→restart→read that tests
  *our volume/permission wiring* (not Jena's durability). Every documented
  extension point gets a test, so the docs stay honest.
- **Supply chain:** vulnerability scan (Trivy/Grype), SBOM, and signing
  (cosign/sigstore + provenance). An unsigned, unscanned "official SP image"
  undercuts the reputational play.
- **Bumps — the new-Jena signal.** Renovate (not Dependabot — it doesn't bump
  arbitrary ARGs well) with a custom manager on the Dockerfile's `JENA_VERSION`
  ARG. Datasource = **Maven Central** `org.apache.jena:jena-fuseki-server` (the
  authoritative "actually released" signal — a git tag can precede the real
  release; Maven publication lands in lockstep with `archive.apache.org`, where
  we pull the dist). `github-tags` on `apache/jena` (`extractVersion=^jena-(?<version>.*)$`)
  is the alternative.

  ```dockerfile
  # renovate: datasource=maven depName=org.apache.jena:jena-fuseki-server
  ARG JENA_VERSION=6.1.0
  ```

  Renovate opens a bump PR → CI re-tests → merge → `on: push` builds + publishes.
  Add `on: schedule` (daily poll) as a fallback and `on: workflow_dispatch` for
  manual. Near-zero ongoing effort — but don't blind-auto-merge a fresh `.0` the
  day it drops.

## Maintenance model & risk

- **The risk:** an *abandoned* "SP official image" is reputationally worse than
  none. Mitigation is automation — the image only earns the SP name if CI keeps
  it green without manual heroics. Name an owner and a deprecation/sunset policy
  so a lapse is graceful, not silent rot.
- **What we vouch for:** clean, current *packaging* — **not** that Jena is
  correct. Testing the engine in depth (write durability, SPARQL/reasoner
  semantics) is Apache's job; chasing the engine's bugs is infinite scope and we
  could never be confident. We don't ship a *knowingly* affected version (see
  the Jena CRUD/write-corruption fix landing in 6.2.0), but we don't QA the
  engine. Overclaiming correctness is the reputational trap, not the cure.
- **Licensing:** Apache Jena is Apache-2.0; repackaging is fine. Named clearly
  as *SP's distribution* — no implied Apache endorsement; Jena's own
  LICENSE/NOTICE ship inside the image. Repo under Apache-2.0 (see `LICENSE`,
  `NOTICE`).

## Milestones

- **v0.1** — minimal + `full` (tier-1 UI) images, bb entrypoint honouring mounted
  `config.ttl`/`shiro.ini` + effective-config dump, env/`*_FILE` secrets,
  non-root, healthcheck, multi-arch (6.x), GHCR publish, two-axis tags, scan +
  sign + SBOM, packaging smoke test, README + documented extension points.
- **v0.2** — `tdb2` storage (+ documented volume/UID contract), `:basic` auth,
  reasoner options, optional EDN/aero convenience layer (decided on merits),
  CodeMirror web-component UI variant (tier 2), Renovate wired.
- **later** — Bet B (bespoke UI tier 3) as its own decision, slotting into the
  same `:ui` seam.

## First adopter (dogfood)

The training-lab devcontainer currently pins `stain/jena-fuseki:5.1.0` for its
Fuseki service while the rest of its toolchain is on Jena **6.1.0**. Once v0.1
ships, point it at `ghcr.io/semantic-partners/sp-fuseki:<6.x>` — immediate
internal consumer, version aligned with the lab, and the course demonstrates
SP's own tooling. Keep the `stain` pin documented as the fallback until v0.1 has
cleared a real cohort.

## Open decisions

- **Config convenience layer:** ship v0.1 on plain assembler TTL (leaning yes);
  add EDN/aero in v0.2 only if TTL ergonomics actually hurt. Decided separately
  from the bb decision.
- Naming: settled on **`sp-fuseki`** under the org (avoids implying Apache
  endorsement). Image tag scheme: two-axis (above).
- UI build: a thin CodeMirror web component (tier 2) likely covers the need;
  skip YASGUI (you'd only fight its wrapper). Web component over React. Tier 3 is
  a "useful couple of days?" call against Lance's bandwidth.
