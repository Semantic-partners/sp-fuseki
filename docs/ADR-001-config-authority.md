# ADR-001: Config authority — gate what we own, assist what we don't

- **Status:** Proposed
- **Date:** 2026-08-13
- **Deciders:** Lance; drafted by Claude from the first external migration onto sp-fuseki
- **Related:** [RFC](RFC.md) → Config; [ASSESSMENT](ASSESSMENT.md) §1 and §9;
  issues #12, #13, #14, #16, #18, #19
- **Evidence:** migrating SPOQE's `chinook` test rig (4 datasets, Lucene text index)
  off `stain/jena-fuseki:5.1.0`, and booting Semantic Partners' Elasticsearch-plugin
  config unchanged on Jena 6.2.

## Context

ASSESSMENT §9 said: *"Don't commit to EDN before feeling the pain it solves."* That was
right, and the pain has now been felt once, in detail, by an actual migration rather than
a thought experiment. This ADR records what that migration found and the rules that fall
out of it.

The through-line: **every defect found was a hole in the same property, and it was never a
Fuseki problem.** The image sells *"you can tell what it will do, before and after it does
it."* Four separate bugs were four surfaces of one gap in that:

| found | the missing thing |
|---|---|
| `:server :port` validated then ignored (#12) | a decision with no logged line |
| `ADMIN_PASSWORD`, `FUSEKI_DATASET_N` ignored (#18) | an input with no logged line |
| `configuration/` silently additive (#19) | a config source with no logged line |
| `:query` renders as `sparql`, so `/ds/query` 404s | a **route** with no logged line |

That is one bug, not four. So the rules below are mostly one rule.

## Decisions

### 0. We are not a new standard. We are a new expression FOR the standard.

This is the frame the rest follows from, and it is the direct answer to ASSESSMENT §9's
xkcd-927 worry — *"Fuseki already has three config surfaces; EDN makes four."*

It doesn't, because the EDN introduces **no vocabulary of its own**. The concepts are
Jena's: a service, a dataset, an endpoint, a TDB2 location, an ARQ context. `fuseki.edn`
is a different *notation* for the same model, and it compiles to the standard artifact.
There is nothing new to learn semantically — only a nicer way to write what you were
already writing.

**JSON-LD is the precedent, and it settles the argument.** Nobody calls JSON-LD a rival to
RDF — it *is* RDF, written in JSON, with `@context` carrying the prefixes. A W3C standard
whose entire job is being a second notation for an existing model. The mapping to what we
are doing is exact: `@context` ↔ `:prefixes`, expansion ↔ rendering, and the compacted form
is the one humans write while the expanded form is the one machines agree on.

And JSON-LD works *because* it invented no semantics. Its keywords — `@id`, `@type`,
`@list` — each name an existing RDF construct. That is the discipline decision 0 imposes,
and it is why the constraint below is the load-bearing part rather than the framing.

It is also precisely SPOQE's position: EDN queries that compile to SPARQL. Not a rival
query language, the same one in a better notation.

The framing is not decoration; it is a **constraint on the schema**:

- **Every key must denote something in the assembler vocabulary.** If a key has no
  referent in Jena's model, it does not belong in the EDN — it would be a new standard,
  which is the thing we are claiming not to be.
- It is why `:context` (decision 3) is right: a direct expression of `ja:context`, not an
  invention.
- It is why the TTL takes precedence (decision 1) and why `config.effective.ttl` is
  written and logged: the renderer is a **compiler**, the assembler TTL is its object
  code, and you can always read the output or drop to writing it yourself. A notation you
  cannot escape from *would* be a new standard, whatever its authors called it.
- It sets what the format's version tracks (decision 4): the vocabulary it expresses, not
  the image that happens to ship a renderer.

**Tripwire:** the first key that means something only to sp-fuseki. At that moment the
claim stops being true and either the key goes or the claim does.

### 1. TTL takes precedence. Reaffirmed, not reopened.

The assembler TTL is the substrate; EDN is a generator over it. When both are mounted the
TTL wins and the entrypoint logs that the EDN was ignored. Nothing in this ADR displaces
that, and the EDN work below must not be read as a drift toward EDN-first.

This matters because the rest of this document argues for *more* EDN. That argument is
about ergonomics inside the generator, not about which artifact is authoritative.

### 2. Gate what we own. Assist what we don't.

- **Our vocabulary** — `:auth`, `:ui`, `:server`, `:datasets` and its keys — we define the
  semantics, so we know what invalid means. **Validate, and refuse to boot.**
- **Someone else's vocabulary** — plugin context keys, raw triples — we do not. **Pass it
  through and advise.**

The justification is authority, not taste: you can only honestly gate what you have
authority over. `:storage :tdb3` is a fact about our own vocabulary. Rejecting a predicate
we don't recognise is us blocking a valid config on our own ignorance — Jena may accept it,
a plugin may define it, the next version may add it. Jena is the authority there, and it
will say so at boot.

**Corollary, and it must be built deliberately: an unrecognised key in our own namespace is
an ERROR, not an implicit escape hatch.** If unknown keys fall through to the assistive
path, a typo becomes indistinguishable from intent — `:datset` stops being a mistake and
becomes "user vocabulary, pass it through", which is the exact failure this project refuses
elsewhere. **The escape hatch is opted into by name**, never inferred from ignorance.

The same rule covers the environment: variables in our namespace that we do not consume
should be reported (#18). Ours, our responsibility — recognise it or complain about it.

**Tripwire:** if any unknown-key path is ever added that silently passes through, this rule
has been broken and #12's bug class is back.

### 3. `:context` is the structured escape hatch — better than raw triples.

Semantic Partners' Elasticsearch plugin extends Fuseki through exactly one mechanism:

```turtle
ja:context [ ja:cxtName "http://schema.synaptica.com/oasis#syncToEsIndex" ; ja:cxtValue "graphite" ]
```

`ja:context` is **not plugin-specific** — it is Jena's generic ARQ-context extension point.
Only the *keys* are unknown to us. So the shape is ours to gate and the keys are not:

```clojure
:prefixes {:oasis "http://schema.synaptica.com/oasis#"}
…
{:name "graphite" :storage :tdb2
 :context {:oasis/syncToEsIndex "graphite"}}
```

We validate that it is a map of IRI → value. We know nothing about what the IRI means, and
we should not pretend to. This covers every ARQ-context plugin rather than one, and it is
strictly better than dropping to raw triples: still structured, still validated for shape,
still loggable.

**Tripwire:** a plugin that extends Fuseki through something *other* than `ja:context`
(a custom assembler class, say) is not covered, and should use the TTL passthrough rather
than motivate a second escape hatch.

### 4. The renderer is one artifact, consumed several ways.

This answers ASSESSMENT §9's *"decouple it from bb"*. The EDN format's reach should not be
limited to people running our container — it targets stock Fuseki, other images, embedded
Jena, anything that eats assembler TTL.

- `docker run --rm -i … render < fuseki.edn > config.ttl` — the widest reach for the least
  work, and it needs no Clojure toolchain, which most Fuseki users do not have.
- A bb git dep for people who do. `entrypoint/` is already pure and separately unit-tested.

**The constraint is the point: the container must DEPEND ON the renderer, never carry a
copy.** The moment the standalone tool and the boot path are separate code they can
disagree, and a green render that boots differently is worse than no tool — the same
validated-then-ignored class as #12, one level up.

**Consequence:** the format needs a name and a version of its own, distinct from the image
tag. Otherwise "which EDN does this renderer speak" is answered with a Docker tag, and
those two will not stay in step.

### 5. aero: revisit narrowly, for `#include`. Keep our strictness.

The RFC records **"`#env`/`#file` reader tags, not aero — pulling aero into a bb script buys
little else."** That was correct on the information available. **This is new information,
not a reversal on the same facts:** the migration produced a concrete multi-dataset,
per-file requirement that did not exist when the call was made.

`#include` is the answer to the config-directory question in #19 — per-file separation
*without* a third config source, without the root-owned-directory trap, and with the
boundary visible in the document instead of inferred from a mount:

```clojure
{:datasets [#include "chinook.edn"
            #include "offshore.edn"]}
```

`#profile` is a second draw: dev/prod variants of one config.

**But adopting aero as-is would downgrade the property this project is built on.** Verified
against aero's source:

```clojure
(defn- get-env [s] (System/getenv (str s)))
(defmethod reader 'env [opts tag value] (get-env value))
```

aero's `#env` returns **nil, silently**, where ours throws. A missing
`FUSEKI_ADMIN_PASSWORD` would stop being a loud boot failure and become nil threaded
quietly into the config — the config-that-lies failure mode, arriving through the front
door.

**Do not fix this by overriding aero's `reader` multimethod.** It is a global `defmethod`;
redefining `'env` changes it process-wide for anything else using aero in the same JVM.
Tolerable in a container, hostile in the standalone library decision 4 commits us to.

**Fix it in validation instead**, which we already have and which produces a better message
because it knows the config path:

```
:auth :password is nil — #env "FUSEKI_ADMIN_PASSWORD" is not set
```

against today's `#env "FUSEKI_ADMIN_PASSWORD" is not set`. The second says what is missing;
the first says what it was for. Moving strictness from read-time to validate-time is an
upgrade.

**Tripwire:** if aero's transitive dependency surface turns out to be non-trivial, this
trades a real property (a dependency-free image with checksummed downloads) for
ergonomics, and the balance flips. Measure before adopting.

### 6. Every resolved decision gets a logged line naming its source — including routes.

The pattern already exists for `auth`, `ui` and `port`. Extend it to everything resolved,
and treat **routes as decisions**:

```
chinook  (validated)   /chinook/query /chinook/update /chinook/data /chinook (root)
weird-ds (raw triples) — not validated
```

The mode matters as much as the routes: gated and assisted config carry different
guarantees, and a reader cannot otherwise tell whether what they are looking at was checked
or waved through.

## Gaps this exposes in the EDN (evidence, not decisions)

Recorded so the work is scoped from a real config rather than a hypothetical. SPOQE's
chinook config cannot currently be expressed in EDN because of three things:

1. **No text index.** `text:TextIndexLucene` plus its entity map — the block whose TTL is an
   RDF list of blank nodes, and therefore the one with the largest ergonomic payoff.
2. **No unnamed endpoints.** `endpoint-lines` only ever emits named ones, so an EDN dataset
   does not answer at its root. Confirmed: `/kb/sparql` → 200, `/kb` → 400. SPOQE needs root
   operations because `spoqe load` targets the dataset URL directly — and so does the ES
   plugin's own config, via `fuseki:serviceQuery "sparql", "query", ""`.
3. **No control over endpoint names, and the default is a live surprise.** `:query` renders
   as `fuseki:name "sparql"`, so `/ds/query` is a 404. This one bites silently and is
   independent of any extension work.

## What was verified, and what was not

Run against `ghcr.io/semantic-partners/sp-fuseki:latest` (Jena 6.2.0, arm64, anonymous pull):

- four datasets incl. a Lucene text index register from one mounted `config.ttl`;
- `text:query` returns hits on 41,174 real triples; both entity-map fields resolve
  independently;
- the ES plugin's production config boots unchanged — legacy `fuseki:serviceQuery` syntax
  and `ja:context` both accepted on 6.2, all three query names including root answering 200;
- `/fuseki/run/configuration` is not pre-created, so mounting a file into it makes Docker
  create it root-owned and Fuseki dies `Not writable` (#19).

**Not verified:** the plugin jar was absent, so `ja:context` was inert — this says the config
parses and registers, not that the plugin works. No SHACL anywhere: `jena-shacl` is not in
`fuseki-server.jar` (0 entries), so validating the rendered graph would be a new dependency,
which is why it is not proposed here.
