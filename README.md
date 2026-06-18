# sp-fuseki

Clean, **config-respecting**, **data-driven** Apache Jena Fuseki Docker images.

The maintained, ergonomic Fuseki image that doesn't exist yet: you mount config,
we honour it; the whole server is described by one data file (`fuseki.edn`)
rendered into Fuseki's assembler config at boot.

> Status: **pre-v0.1**. This repo currently holds the design and an example
> config. No image is published yet. See [docs/RFC.md](docs/RFC.md).

This is *Semantic Partners' distribution* of Apache Jena Fuseki. Apache Jena is
Apache-2.0; this repackaging does not imply Apache endorsement. See
[NOTICE](NOTICE).

## Why

There is no maintained, ergonomic Fuseki image. `stain/jena-fuseki` is
unmaintained (tags stop at 5.1.0); `apache/jena-fuseki` is bare with an awkward
config story; `secoresearch/fuseki` regenerates config from env and **ignores a
mounted `config.ttl`**. Spinning up a triplestore is a papercut every time.

## Layout

| Path | Contents |
|---|---|
| [docs/RFC.md](docs/RFC.md) | The design RFC — goals, bets, milestones. |
| [docs/ASSESSMENT.md](docs/ASSESSMENT.md) | Gaps & weakpoints review of the RFC. |
| [examples/fuseki.edn](examples/fuseki.edn) | The data-driven config example. |
| `image/` | Dockerfile + build (stub — v0.1). |
| `entrypoint/` | babashka boot + config renderer (stub — v0.1). |

## v0.1 scope

Minimal image: babashka entrypoint renders `fuseki.edn` → Fuseki assembler
config, non-root, healthcheck on `/$/ping`, multi-arch (amd64+arm64), published
to `ghcr.io/semantic-partners/sp-fuseki`, CI smoke test. Tracking issue: TBD.

## First adopter

The `training-data` devcontainer (Apache Jena 6.1.0 toolchain) currently pins
`stain/jena-fuseki:5.1.0` for its Fuseki service. v0.1 repoints it here —
aligning the Fuseki version with the rest of the lab toolchain in the process.

## License

[Apache-2.0](LICENSE).
