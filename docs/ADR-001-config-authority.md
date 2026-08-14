# ADR-001: Config authority — gate what we own, assist what we don't

- **Status:** Accepted. Most of it shipped — #22 (endpoint naming, root endpoints, `#include`,
  the `configuration/` fix, route logging, duplicate-name refusal) and the `:text` work that
  followed, including the module probe from 5b. **Decision 4 (the renderer as a standalone
  artifact) is the outstanding piece** and is not built.
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

A real Elasticsearch sync plugin we work with extends Fuseki through exactly one
mechanism — a single ARQ context key:

```turtle
ja:context [ ja:cxtName "http://example.org/plugin#syncToEsIndex" ; ja:cxtValue "kb" ]
```

`ja:context` is **not plugin-specific** — it is Jena's generic ARQ-context extension point.
Only the *keys* are unknown to us. So the shape is ours to gate and the keys are not:

```clojure
:prefixes {:plugin "http://example.org/plugin#"}
…
{:name "kb" :storage :tdb2
 :context {:plugin/syncToEsIndex "kb"}}
```

The IRI here is deliberately `example.org`: the whole point of this decision is that we do
not know what the key means, so the real vendor's namespace adds nothing and is not ours to
publish.

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

### 5. `#include` as our own tag. Requirement accepted, aero rejected.

The RFC records **"`#env`/`#file` reader tags, not aero — pulling aero into a bb script buys
little else."** That was correct on the information available, and it stands. **What is new
is the requirement, not a reason to reverse the vehicle:** the migration produced a concrete
multi-dataset, per-file need that did not exist when the call was made.

`#include` answers the config-directory question in #19 — per-file separation *without* a
third config source, without the root-owned-directory trap, and with the boundary visible in
the document instead of inferred from a mount:

```clojure
{:datasets [#include "chinook.edn"
            #include "offshore.edn"]}
```

We implement it ourselves, next to `#env` and `#file`.

#### Why not aero, having seriously considered it

Recorded because the option is attractive and will be proposed again.

1. **It fails decision 0, four sections earlier.** Adopting aero adopts aero's *whole tag
   vocabulary* — `#profile`, `#ref`, `#or`, `#merge`, `#long`, `#join`, `#hostname`. The
   moment aero parses `fuseki.edn` they all work. None denotes anything in Jena's assembler
   model, so each is a key that means something only to sp-fuseki. An earlier draft of this
   ADR offered `#profile` as *a second draw in favour* — that was the strongest argument
   against, written down as a benefit, and it is the clearest illustration of why decision 0
   has to be applied rather than admired.
2. **The README's claim goes false immediately.** *"Every extension point is documented below
   and exercised by the smoke test."* Seven undocumented, un-smoke-tested tags would arrive in
   one commit. Emergent undocumented extension points is precisely what the RFC criticises the
   incumbents for.
3. **The image has no dependency mechanism at all.** `BABASHKA_CLASSPATH=/opt/sp-fuseki`,
   `ENTRYPOINT ["bb", "/opt/sp-fuseki/entrypoint.clj"]` — a bare script, no jars, no
   resolution step, and `test/dockerfile_test.clj` fails the build if an artifact is fetched
   without a hash. Aero means either vendoring third-party source into a boot path sold as
   short and readable, or adding a Clojars fetch-and-verify stage.

An earlier draft priced this wrong: its tripwire was *"if aero's transitive dependency
surface turns out to be non-trivial"*. That was never the binding constraint. The constraint
trips on **adoption**, not on transitive weight — a zero-dependency aero would still fail (1)
and (2).

**`#include` survives all three because it is a mechanism, not vocabulary** — the same class
as `#env` and `#file`, which resolve to a value rather than naming a Jena concept. That is
the line, and it is the test to apply to the next tag anyone proposes.

**What we give up, and must therefore build:** aero had already solved cycle detection,
relative-path resolution and a depth limit. Roughly thirty lines and a test each. Path-escape
confinement is deliberately *not* built: whoever writes `fuseki.edn` already controls the
whole config and could mount a `config.ttl` instead, so confining `#include` would be theatre
that breaks legitimate absolute-path includes.

**Tripwire:** the next proposal to adopt a config library wholesale. The question to ask is
not "how heavy is it" but "how many tags does it bring that denote nothing in Jena's model".

#### `#env` strictness stays at read time. Moving it to validation: considered, rejected.

Aero's `#env` is `(System/getenv s)` and returns **nil, silently**, where ours throws. An
earlier draft proposed moving our strictness to validation, because a validator knows the
config path and can say what the value was *for*:

```
:auth :password is nil — #env "FUSEKI_ADMIN_PASSWORD" is not set     (proposed)
#env "FUSEKI_ADMIN_PASSWORD" is not set                              (today)
```

The message really is better. It was proposed as the mitigation aero would have forced; with
aero rejected it became optional, and on inspection it should not be taken:

1. **It trades a guarantee for prose.** The read-time throw *is* the "refuses to boot rather
   than half-configuring" claim. Weakening it so a later stage can phrase the failure better
   is backwards.
2. **It creates a window where the config-that-lies exists by construction.** Between read
   and validate, nil sits in the map. Nothing looks at it today — which is exactly the shape
   #12 had.
3. **The decider: it replaces a rule that cannot be forgotten with one that must be
   remembered.** The read-time throw is a single rule covering every key, including keys
   nobody has written yet. Validation-time strictness is per key, so the first key added
   without a nil check silently accepts nil. In a project whose pitch is that the seams are
   stated rather than emergent, that is the wrong direction — and it fails open.

The message can be improved without the trade: the reader can say what to do next
(`#env "X" is not set in the environment — set it, or use #file`) rather than only what is
missing. It cannot know the config path, so it is less good, and it costs nothing.

**If aero is ever adopted after all:** do not restore strictness by overriding aero's
`reader` multimethod. It is a global `defmethod`, so redefining `'env` changes it
process-wide for anything else using aero in the same JVM — tolerable in a container,
hostile in the standalone library decision 4 commits us to.

### 5b. Module vocabulary: we validate what we can see, and we say so.

Raised by `:text`, and written before it was built — because it is a **limit on the central
promise**, and limits are worth stating before someone discovers them. `:text` and the probe
have since shipped; the decision below is what was implemented.

Every EDN key so far denotes **core assembler vocabulary**: `fuseki:Service`,
`tdb2:DatasetTDB2`, `ja:context`. Present by definition in any Fuseki, so validating a
config against our schema is the same as knowing it will assemble.

`text:` is different. It denotes vocabulary from **jena-text, a module**. It happens to be
in `fuseki-server.jar` today — verified, our Lucene index answers 102 hits over 41,174 real
triples — but that is a property of the build, not of Fuseki.

This does **not** break decision 0: `text:` is Jena's vocabulary, not ours. What it breaks
is subtler and worth naming plainly:

> **"Refuses to boot rather than half-configuring" becomes "refuses to boot for the things we
> can see."** A `:text` config on a build without jena-text passes our validation and then
> dies at Jena. We would be certifying a config we cannot confirm is servable.

And the failure it dies with is not one the user can act on. An assembler type Jena does not
recognise produces:

```
NoSpecificTypeException: the root file:///fuseki/run/config.effective.ttl#t-wrapped
has no most specific type that is a subclass of ja:Object
```

That names a node in a **generated** file the user never wrote, and explains itself in terms
of `ja:Object` subclassing. Someone whose `fuseki.edn` had a `:text` block would have no path
from that message back to their own config, let alone to "the image lacks jena-text".

Two honest responses, and they are not exclusive:

1. **Probe at boot.** Before rendering `:text`, check the assembler class resolves; if not,
   fail with our own message naming the key and the module. Cheap, and it keeps the promise
   whole for the case we know about.
2. **State the limit.** Any key whose vocabulary comes from a module carries the caveat that
   validation cannot confirm availability. Document which keys those are.

**Decision: do (1) for each module-backed key we add, and (2) in the README once such a key
exists.** (1) alone is a promise we would silently break for the next module; (2) alone
hands the user the `NoSpecificTypeException` and a doc to read afterwards.

**The probe is a jar inspection, not a classload — and this matters, because the obvious
implementation is impossible.** The entrypoint is babashka and Jena is not on its classpath;
`fuseki-server.jar` is handed to a separate `java` process at exec time. So `Class.forName`
is unavailable at validation time, and shelling out to `java` to ask would cost a JVM start
on every boot. Reading the jar as a zip works, in-process, at negligible cost:

```clojure
(with-open [z (java.util.zip.ZipFile. jar)]
  (some? (.getEntry z "org/apache/jena/query/text/assembler/TextDatasetAssembler.class")))
```

`TextDatasetAssembler` specifically, not `TextIndexLuceneAssembler` — both are in the jar, but
the rendered TTL declares `fuseki:dataset [ a text:TextDataset ]`, so that is the type whose
absence produces the `NoSpecificTypeException` above.

Written down because someone reaching for `Class.forName`, finding it impossible from bb, and
quietly downgrading to documentation-only is precisely the outcome the tripwire below exists
to catch.

**Tripwire:** the first module-backed key that ships without a boot probe. At that point
"refuses to boot rather than half-configuring" is no longer true as written and the README
sentence has to change rather than the behaviour being quietly excused.

#### What `:text` actually cost, now that it is built

Shipped, probe included. Two things it taught that the vocabulary does not tell you, and one
question it leaves for the next module.

**A notation for the standard sometimes has to know more than the standard says.** The index
and entity map **cannot be inline blank nodes**. `jena-text`'s `EntityDefinitionAssembler`
reads the entity map through `ParameterizedSparqlString.setIri`, so a blank node arrives as a
null IRI and boot dies:

```
NullPointerException: Argument to NodeFactory.createURI is null
  at EntityDefinitionAssembler.open(EntityDefinitionAssembler.java:83)
```

Inline blank nodes work everywhere else in the assembler, which is exactly why a generator
gets this wrong — the first implementation rendered them inline, as every other block does,
and the container died. They have to be named resources, which is why every hand-written
example you will find names them.

This is a genuine qualification on decision 0. "A notation for the standard" suggests a
mechanical transform, and mostly it is; here the renderer has to encode a fact about a
*module's assembler implementation* that no amount of reading the vocabulary would reveal.
The notation is still not a new standard — but it is not free of target knowledge either, and
that knowledge is invisible until something dies at boot.

**`:text` with `:reasoner` is refused.** Whether the index should see entailed triples is a
real decision nobody has made. Refused rather than guessed, consistent with `:reasoner` on
`:tdb2`. Mount a `config.ttl` if you need both.

**The question for module #2.** `:prefixes` + `:fields` is a context plus compact IRIs — which
makes this a domain-specific EDN-LD, and that framing is right and deliberately bounded:
going fully general would destroy the validation that *is* the product (see decision 0's
rejection of arbitrary triples). But `:text` is the first key mapping a vocabulary we do not
own, and it cost roughly fifty lines of hand-mapping. Every module after it costs the same.

**Tripwire: module #2.** At that point the question is no longer "should we support this
module" but "are we hand-mapping a vocabulary per module forever, and is that the business we
are in". Worth answering deliberately, before the second one is already written.

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

## Gaps this exposed in the EDN — all three now closed

Recorded so the work was scoped from a real config rather than a hypothetical. SPOQE's
chinook config could not be expressed in EDN because of three things. **All three shipped,
and the full four-dataset config now expresses** — verified against the published image with
41,174 real triples loaded (see below).

1. ~~**No text index.**~~ **Shipped.** `text:TextIndexLucene` plus its entity map — the block
   whose TTL is an RDF list of blank nodes, and therefore the one with the largest ergonomic
   payoff. See "What `:text` actually cost" in 5b for the two things it taught.
2. **No unnamed endpoints.** `endpoint-lines` only ever emits named ones, so an EDN dataset
   does not answer at its root. Confirmed: `/kb/sparql` → 200, `/kb` → 400. SPOQE needs root
   operations because `spoqe load` targets the dataset URL directly — and so does the ES
   plugin's own config, via `fuseki:serviceQuery "sparql", "query", ""`.
3. **No control over endpoint names, and the default is a live surprise.** `:query` renders
   as `fuseki:name "sparql"` (`render.clj:56`), so `/ds/query` is a 404. This one bites
   silently and is independent of any extension work.

   **The fix is name control plus root support, NOT moving `:query` to `/ds/query`.**

   An earlier draft justified that by back-compatibility — the package went public on
   2026-08-13, so moving the default would move URLs under existing users. **That reasoning
   was overruled, and it was wrong: we are the only user, so back-compat is not the
   constraint.**

   The right reason is decision 0. `sparql` is **Fuseki's** default. Shipping an alias Fuseki
   does not have would make this an improved Fuseki rather than a notation for one — the same
   discipline that keeps `#profile` out. Same outcome, and the reason is the part that
   generalises: the next "wouldn't it be friendlier if…" gets measured against whether Fuseki
   does it, not against who might break.

   Callers who want `/chinook/query` ask for it explicitly. Ours do.

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
parses and registers, not that the plugin works.

**Correction — an earlier revision of this ADR said `jena-shacl` is not in
`fuseki-server.jar` (0 entries) and that a rendered-graph SHACL backstop would therefore be a
new dependency. That is false, and the method that produced it was broken.** The count came
from `unzip -l … | grep -c shacl` run inside the image — and `unzip` is not installed there,
so the command failed and `grep -c` faithfully counted zero lines. Absence of a tool, read as
absence of evidence. The same `java -cp … org.apache.jena.shacl.cmds.shacl` throwing
`ClassNotFoundException` seemed to corroborate it; that class really is absent, because the
CLI entry point is not shipped, and it says nothing about the library.

Measured properly, by enumerating the zip:

```
total entries in jar: 33736
org/apache/jena/shacl/       entries: 198
org/apache/jena/query/text/  entries:  71
shacl CLI main class present: false
```

So **SHACL is available and costs no new dependency.** It is still not proposed for *input*
validation, for the reason given elsewhere — it reports against a graph the user never wrote,
and the input is EDN. A rendered-graph backstop is cheaper than previously stated, but it is
not free operationally: bb has no Jena on its classpath, so it would mean a JVM invocation
or folding the check into the server launch. Worth revisiting on its merits, not on the cost
figure that was wrong.

The technique that corrected this is the same jar inspection proposed for the module probe in
5b, which is a reasonable argument for building it.

Added after `fix/endpoints-include-configdir` (`e3788d3`), all run against a container built
from that branch:

- our four datasets express through `:endpoints` map form, including **three operations at
  the root**, which the hand-written TTL relies on: `/chinook/query` 200, `/chinook` 200,
  `POST` update to `/chinook` 204, `/chinook/data` 200, and `/chinook/sparql` correctly 404
  because we did not ask for it;
- `#include` resolves relative to the includer; cycles print the trail, missing includes give
  the resolved path, depth is capped, and an ambiguous name is refused;
- `FUSEKI_EDN`, `FUSEKI_CONFIG` and `FUSEKI_SHIRO` set to a nonexistent path each abort
  rather than falling back to a generated default — **the hole was three variables wide, not
  one**, and the shiro case was the worst of them because it silently replaced supplied
  access rules;
- long values in validation messages truncate at 100 characters with the true length
  appended, so a mistyped `#include` of a secrets file cannot spill it into a log that
  travels further than the config does;
- `text:entityField` is **required by Jena** — an EntityMap without it fails
  `Failed to find a valid EntityMap` — so it is a constant to emit, not a key to expose.

Final run, against the **published** `ghcr.io/semantic-partners/sp-fuseki:latest` with nothing
built locally — the whole chinook rig, all four datasets, as `fuseki.edn`:

```
routes: chinook  -> gsp-rw /chinook/data /chinook | query /chinook/query /chinook | update …
routes: offshore | tags | batches                     (same shape)
41,174 triples loaded into chinook
text:query "love"                    102 hits
(rdfs:label "love") / (dc:title "love")  102 / 102
(skos:prefLabel "rock")                5
"zzzznotaword"                         0     — an index that matches everything is not an index
```

Those three 102s look like field scoping doing nothing, so they were checked rather than
accepted: distinct probe terms inserted under each predicate resolve **only** through their
own field (`(rdfs:label "zebracrossing")` 1, `(dc:title "zebracrossing")` 0, and the
default field does not see a `dc:title`-only term). The identical counts are the chinook data
carrying "love" in both predicates on the same entities, not a broken entity map.
