# RFC assessment — gaps & weakpoints

Review of [RFC.md](RFC.md). The RFC is solid and the bet is well-framed; what
follows is where it is thin, ambiguous, or load-bearing on an unstated
assumption. Ordered roughly by how much each could hurt v0.1.

## What we're actually offering (the thesis)

The RFC names the differentiator as "the whole server as EDN — pure SP 'it's
just data'." Once we lean TTL-first (weakpoint #9), that's gone, and the obvious
question is: what's left that isn't just "`apache/jena-fuseki` with our logo"?

The answer isn't the config *format* and isn't ops polish (copyable, thin moat).
It's **the extension contract, documented** — instead of reverse-engineered from
five layers of entrypoint bash.

The real pain with stain / secoresearch / the official image isn't that they
don't work. It's that to learn *how to configure or extend* them you spelunk
undocumented entrypoint scripts and guess: which mount does what, which env var
triggers what, why your mounted config got ignored. The extension points are
*emergent from the script*, never *stated*.

So the product is **the contract, written down and tested**:
  - what you can mount and exactly what each does (`config.ttl`, `shiro.ini`,
    data dir, and `fuseki.edn` if EDN ever lands);
  - every env var and its effect, including the `*_FILE` secret convention (#4);
  - where the entrypoint writes the rendered config, so you can inspect it (#1);
  - recipes for the common moves — add a dataset, switch to TDB2, enable a
    reasoner, turn on auth, enable the UI — not bash-spelunking.

Two earlier decisions become load-bearing under this thesis:
  - **bb-over-bash (#7) is now justification, not preference.** A legible
    entrypoint is one whose seams you *can* document and keep honest. Bash
    accretion is exactly what made the incumbents un-documentable — and what
    made them rot. We're selling against the thing bash produces.
  - **TTL-first (#9) stops being a moat-killer.** The moat was never the syntax;
    it's "the seams are documented." Documented TTL beats EDN you still have to
    reverse-engineer.

**The strong version — make "documented" a tested claim, not prose:** every
documented extension point gets a smoke test that exercises it (ties to #6).
Then the docs are executable-backed; they can't drift from the entrypoint.
That's the moat ops-quality alone lacks — people choose it because they can
*understand and trust* it, and the trust is green on every build.

This subsumes the config-as-product vs ops-utility fork: the config model and
the ops defaults are both just *parts of the documented contract*. The contract
is the product. (This should flow back into the RFC's **Why** / **Goals** —
right now the RFC sells EDN; it should sell legibility.)

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

### 10. The UI non-goal conflates "build" with "bundle" — reopen tiers 1–2
The RFC excludes the UI as "weeks of frontend + ongoing maintenance, keep it
separate." That reasoning is **only true for a greenfield UI.** It wrongly kills
two much cheaper options along with it. There are three tiers, not one bet:

| Tier | What | Build cost | Maintenance |
|---|---|---|---|
| **1. Keep Fuseki's own UI** | A `full`/`lab` variant that doesn't strip the webapp Fuseki already ships | ~zero (don't pass the strip flag) | bumps with Jena; Apache maintains it |
| **2. Bundle YASGUI** | Serve the mature OSS query UI pointed at the local endpoint | low/med — base paths, CSP, pinning (the "plugin hell") | Renovate-able; static assets, no server surface |
| **3. Greenfield CodeMirror-6 UI** | The "YASGUI-killer" | weeks | ongoing — the one that *can't* clear the auto-green bar |

The RFC's "separate product" argument applies to **tier 3 only**. Tiers 1–2 are
image features, and tier 1 is nearly free.

This matters for the thesis: stain stayed popular *despite* being abandoned
because it's "Fuseki that just runs, **with a window into your data.**" The real
papercut isn't "I need a triplestore API," it's "spin one up, load data, and
*poke at it*" — demo, lab, client spike. An API-only `minimal` image doesn't
solve that; a batteries-included one does. **The UI option may be the
differentiator, not scope creep.**

Under the documented-contract thesis, the UI is just another **documented,
tested extension point**: "set this → get the Fuseki UI / YASGUI; here's exactly
what it exposes and how auth interacts." The RFC's `:ui {:enabled …}` seam is the
right hook for all three tiers.

- **Lean:** `minimal` + `full` (tier 1) variants in v0.1/v0.2 — the RFC already
  has the variant idea, it just deferred the wrong things. Tier 2 (YASGUI) a
  considered v0.2 variant. Tier 3 stays Bet B, its own project — it's a
  *product*, not a *seam*.
- **Caveat:** UI + `:anon` + an exposed update endpoint is a footgun. The UI
  variant must be auth-aware by default (ties to #4).

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
9. **Rewrite Why/Goals around the thesis:** the offering is a *documented,
   tested extension contract* (legibility), not EDN. Lead with that.
10. **Reopen the UI as tiers:** keep Fuseki's own UI in a `full` variant (tier 1,
    ~free); YASGUI a v0.2 variant (tier 2); greenfield stays Bet B (tier 3).
    Make the UI a documented, auth-aware extension point.
