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

### 4. Auth secrets — a shiro-rendering concern, not a config-format one
`:basic` auth needs users + passwords. Best practice here churns and there's no
clear winner: encrypted-in-repo (SOPS/age/sealed-secrets — a legitimate
"diffable secrets" school), Vault, env vars, Docker/K8s secrets. **An image must
not pick a side** — don't ship a Vault client or a SOPS integration.

Crucially, the credential never lives in the *assembler config* (TTL or EDN) at
all — it lives in `shiro.ini`, which the entrypoint generates regardless of
config format. So the secret story is a property of the **shiro-rendering step**,
not of the config syntax. Two format-agnostic mechanisms cover all four schools,
because every secrets manager ultimately delivers to env or a file:
  - **env / `*_FILE` convention** read by the entrypoint at boot — e.g.
    `FUSEKI_ADMIN_PASSWORD` or `FUSEKI_ADMIN_PASSWORD_FILE` (the Docker secrets
    idiom). Covers 12-factor env, K8s env-from-secret, vault-agent, Docker/K8s
    file mounts. No secret syntax in any committed config.
  - **mount your own `shiro.ini`** (passthrough — same contract as weakpoint #1).
    This *is* the diffable-secrets path: SOPS-decrypt your `shiro.ini` at deploy
    and mount it.

The image stays out of the religion either way. (If — and only if — config later
goes EDN, aero's `#env`/`#file` reader tags are a nice-to-have sugar over the
same env/file sources; they're not the mechanism, and they don't exist under
TTL-first.) The only real no is a plaintext password baked into a committed
config or the image.

### 5. Non-root + mounted TDB2 volume = classic permission footgun
v0.2 adds `:tdb2`. Non-root container + host-mounted volume means UID mismatch
on the data dir — the #1 Docker triplestore support ticket. **Document the
expected UID/`--user` story and the data-dir ownership contract** before TDB2
ships, or first-run will fail confusingly.

### 6. Scope the smoke test to *our* packaging, not Jena's correctness
Testing that Fuseki *works in depth* — write durability, SPARQL semantics,
reasoner correctness — is **Apache's job, not ours.** We're a distribution, not
a fork; chasing the engine's bugs is infinite scope and we'd never be confident
anyway. The Jena CRUD bug (corrupt write, fixed in 6.2.0) is a case in point:
not ours to catch, ours to *make the fix available promptly*. Overclaiming "the
SP image means Fuseki is correct" is the reputational trap, not the cure.

So the smoke test asserts the **packaging contract only**:
  - the config renders and the server boots;
  - declared endpoints respond (POST turtle → query it back);
  - a mounted config is honoured (not silently regenerated — weakpoint #1);
  - non-root runs, healthcheck passes, both arches boot;
  - a TDB2 dataset *survives a container restart* — but this tests our
    **volume/permission wiring** (weakpoint #5), not Jena's durability. Write one
    triple, restart, read it back: a packaging check, not a QA suite.

Renovate auto-bumps then ship whatever upstream shipped, bugs included — which is
correct for a distribution. The mitigation isn't a deeper smoke test; it's
honest scoping of what the SP name vouches for (clean, current packaging) plus
not blind-auto-merging a fresh `.0` the day it drops.

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

### 9. EDN config risks being the Nth config standard — decouple it from bb
Fuseki already has three config surfaces (assembler TTL, env-config, the
`FUSEKI_CONF` conventions). EDN makes four — xkcd 927. This is the real risk in
the design, more than secrets.

Two reframes defuse it:
  - **It's the same issue as weakpoint #1.** The TTL passthrough escape hatch is
    the answer to "are we trapping people in a proprietary standard?": the
    assembler TTL is the always-available substrate; EDN is a *convenience
    generator* over it for the 80% case. Anything EDN can't express, drop to TTL
    — so we never have to chase Jena's full assembler surface in our schema.
  - **The RFC bundles two independent decisions.** "bb *because* EDN is its
    natural home" conflates them. bb's value is orchestration (weakpoint #7) and
    stands *even if config stays TTL.* So the EDN question can be decided on its
    own merits, which opens a cleaner v0.1: **config = assembler TTL (no new
    standard, config is already RDF — maximally "it's just data"); bb just
    orchestrates** (validate, render `shiro.ini` from the env/`*_FILE`
    convention, healthcheck, exec). The "badly defined TTL semantics" complaint
    then becomes
    a *docs* job (document the vocab, ship worked examples), not a build-a-
    language job.

**Lean: TTL-first for v0.1.** Zero new standards, ships faster, the dogfood
config is already TTL so the renderer is near-empty. Add the EDN/aero layer in
v0.2 *only if* TTL ergonomics actually hurt in practice — by then the passthrough
exists, so EDN is safe to bolt on. Don't commit to EDN before feeling the pain
it solves.

## Secondary gaps

- **Config validation / fail-fast.** Malformed `fuseki.edn` should fail loudly
  with a clear message and a dump of what it tried to render — not boot broken.
- **Observability.** Only healthcheck is mentioned. Fuseki exposes
  `/$/metrics` (Prometheus) and uses log4j2 (configurable, and a security
  surface — log4shell). Decide what's wired and what's documented.
- **EDN vs TTL config (open decision).** Promoted to weakpoint #9 — TTL makes
  "config-respecting" trivially true and adds no new standard; EDN buys
  ergonomics. Decide it separately from the bb decision.
- **Version alignment in the dogfood.** The `training-data` devcontainer runs
  the Jena **6.1.0** CLI toolchain but a **5.1.0** Fuseki service. v0.1 should
  target 6.x to align them; repointing at a 5.1.0 image would re-freeze the
  mismatch. (Pick the version deliberately given the 6.2.0 CRUD fix — we don't
  test for it, but we shouldn't ship a knowingly-affected version either.)
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
4. Add a **secrets** subsection: shiro-rendering reads env/`*_FILE` (or mount
   your own `shiro.ini`); image stays backend-agnostic; no committed plaintext.
5. Scope the smoke test to the **packaging contract** (boot, endpoints,
   config-honoured, restart-persists wiring) — *not* Jena correctness.
6. Fix the **bb rationale**: "a real orchestration language vs bash, at
   near-bash cost," not "fast startup." Note the rejected alternatives.
7. Add **scan + SBOM + sign** to the CI section.
8. **Decide config format separately from bb.** Lean TTL-first for v0.1 (no new
   standard); revisit EDN/aero for v0.2.
