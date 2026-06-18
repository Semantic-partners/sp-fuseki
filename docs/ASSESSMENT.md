# RFC assessment — gaps & weakpoints

Review of [RFC.md](RFC.md). The RFC is solid and the bet is well-framed; what
follows is where it is thin, ambiguous, or load-bearing on an unstated
assumption. Ordered roughly by how much each could hurt v0.1.

## What's already right (don't relitigate)

- **Bet A / Bet B split** (image vs UI) is the correct cut. Keeping the UI out
  is what makes the maintenance promise credible.
- **from-dist + `archive.apache.org`** is the right call and avoids a real trap
  (`dlcdn` 404s pinned versions). The `training-data` devcontainer already
  proves this pattern works.
- **Dogfooding** an existing internal consumer is the strongest possible
  validation signal.
- Non-root, healthcheck, multi-arch, pinned, smoke-tested — all correct defaults.

## Top weakpoints

### 1. "Config-respecting" vs "data-driven rendering" is an unresolved tension
The two headline goals can contradict. "You mount config, we honour it; never
regenerate" — but the entrypoint *renders* `config.ttl` from `fuseki.edn`. So
which wins when both a raw `config.ttl` and a `fuseki.edn` are mounted? This is
exactly the failure mode the RFC criticises `secoresearch/fuseki` for. **Needs
an explicit precedence + escape-hatch contract:**
  - raw `config.ttl` mounted → pass through untouched (pure config-respecting).
  - `fuseki.edn` mounted, no `config.ttl` → render.
  - both → error, or documented winner. Don't leave it implicit.
  - Always write the rendered config to a known path and log it, so "what did it
    actually generate" is never a mystery.

### 2. Image tag scheme conflates SP image version with Jena version
The RFC's dogfood says "point it at `…/sp-fuseki:5.1.0`". But the SP layer
(entrypoint, renderer, base) will have its own bugs and fixes that have **no
version space** under a pure-Jena tag. You will ship an entrypoint fix for
Jena 6.2.0 and have nowhere to put it. **Use a two-axis scheme**, e.g.
`sp-fuseki:6.2.0-1` (jena-version + sp-build) or semver-for-the-image with a
`jena=6.2.0` label, plus moving `6.2.0` and `latest`. Decide before the first
publish — retagging after adopters pin is painful.

### 3. `:federation` is under-defined — SPARQL federation is a query-time concern
`:federation [{:name "dbpedia" :url …}]` implies server-side config, but
`SERVICE` is a runtime clause in the query, not server state. What does this key
actually *do* at boot? Plausible real meanings: pre-register prefixes for
federated endpoints, set up a federated/union dataset, or seed a query library.
As written it's hand-wavy. **Define it concretely or cut it from v1** — shipping
a config key that does nothing erodes the "config as data" credibility.

### 4. Auth secrets have no home
`:auth {:mode :basic}` needs users + passwords. Putting credentials in a
diffable, version-controlled `fuseki.edn` is a footgun. **Need a secrets story:**
env-var interpolation (`:password #env FUSEKI_PW`), a separate mounted
`shiro.ini` that wins over rendering, or reading from Docker/K8s secrets. This
collides with the "one file describes everything" aspiration — acknowledge that
secrets are the documented exception.

### 5. Non-root + mounted TDB2 volume = classic permission footgun
v0.2 adds `:tdb2`. Non-root container + host-mounted volume means UID mismatch
on the data dir — the #1 Docker triplestore support ticket. **Document the
expected UID/`--user` story and the data-dir ownership contract** before TDB2
ships, or first-run will fail confusingly.

### 6. Smoke test depth vs semantic regressions
The lance comment is the tell: a Jena CRUD bug landed where "writes left a
corrupted parquet," fixed in 6.2.0. A shallow in-memory POST-then-query smoke
test would **not** catch a TDB2 write-durability regression. Renovate auto-bumps
are only as safe as the smoke test is deep. **The smoke test must include a
persistent (TDB2) write → restart → read-back round-trip**, not just in-memory.
Otherwise "near-zero ongoing effort" auto-merges a corrupting Jena release.
(Side note: clarify the "parquet" detail — stock TDB2 isn't parquet-backed;
which storage/context was this? It affects whether this image is even exposed.)

### 7. babashka entrypoint — state the rationale correctly
The RFC sells bb on "fast startup," which is the weak argument: you're starting
a JVM for Fuseki regardless, so entrypoint startup time is noise. The real case
is **a real language instead of bash to orchestrate non-trivial boot logic, at
near-bash cost** — bb starts fast enough to feel like a shell script but gives
you EDN parsing, data structures, and proper error handling for the
render-config-then-exec flow. Boot orchestration that would be brittle in bash
(parse config → validate → template TTL + shiro → exec) is exactly what bb is
good at. Say *that*. The honest counterweight: bb adds a pinned, arch-specific,
security-patchable dependency (the only non-Java custom piece in a multi-arch
build). The v0.1 rendering surface is tiny (the dogfood config is two datasets +
a handful of endpoints), so the orchestration argument is strongest as the
config grows — note that the value compounds with v0.2 (reasoners, auth, tdb2),
not at v0.1.

### 8. Supply chain / provenance unaddressed
For a *public, SP-branded "official"* image, the bar is higher than build+push.
Missing: image vulnerability scan (Trivy/Grype) in CI, SBOM generation, and
signing (cosign/sigstore + provenance attestation). An unsigned, unscanned
"official SP image" undercuts the reputational play that motivates the project.

## Secondary gaps

- **Config validation / fail-fast.** Malformed `fuseki.edn` should fail loudly
  with a clear message and a dump of what it tried to render — not boot broken.
- **Observability.** Only healthcheck is mentioned. Fuseki exposes
  `/$/metrics` (Prometheus) and uses log4j2 (configurable, and a security
  surface — log4shell). Decide what's wired and what's documented.
- **EDN vs TTL config (open decision).** Note that choosing TTL would make
  "config-respecting" trivially true (you mount the actual assembler config, no
  render step) at the cost of the EDN ergonomics. The decision is really
  "ergonomic data layer" vs "zero translation distance" — frame it that way.
- **Version alignment in the dogfood.** The `training-data` devcontainer runs
  the Jena **6.1.0** CLI toolchain but a **5.1.0** Fuseki service. v0.1 should
  target 6.x to align them; repointing at a 5.1.0 image would re-freeze the
  mismatch. (Pick the version deliberately given weakpoint #6's 6.2.0 fix.)
- **`latest` moving tag** invites non-reproducible pulls; ship it but document
  "pin in anything that matters" (the devcontainer already models this).
- **Maintenance bus-factor.** "Automation mitigates abandonment" is necessary,
  not sufficient — CI keeps it *building*, not *cared for*. Name an owner and a
  deprecation/sunset policy so a lapse is graceful, not a silent rot of an
  SP-branded artifact.
- **Sequencing risk.** Dogfood adopter (the course) may need to ship before the
  image is battle-tested. Keep the `stain` pin as the documented fallback until
  v0.1 has cleared a real cohort.

## Suggested RFC edits before v0.1

1. Add a **"Config resolution" section**: raw-TTL passthrough vs EDN-render
   precedence, and always-dump-rendered-config.
2. Replace the dogfood tag example with the **two-axis tag scheme**.
3. Either **define or cut `:federation`** for v1.
4. Add a **secrets/`:basic` auth** subsection.
5. Expand the smoke test to a **TDB2 durability round-trip**.
6. Fix the **bb rationale**: "a real orchestration language vs bash, at
   near-bash cost," not "fast startup." Note the rejected alternatives.
7. Add **scan + SBOM + sign** to the CI section.
