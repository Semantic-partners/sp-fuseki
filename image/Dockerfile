# syntax=docker/dockerfile:1
#
# sp-fuseki — clean, config-respecting Apache Jena Fuseki.
# Built from the pinned Jena dist (archive.apache.org — permanent), Temurin 21,
# babashka entrypoint. Multi-arch (amd64 + arm64), non-root, healthchecked.

# --- versions (Renovate-managed) ---
# This is the DEFAULT Jena — the leg CI tags `latest`. CI also builds the older
# versions listed in EXTRA_JENA (.github/workflows/build.yml) via --build-arg.
# renovate: datasource=maven depName=org.apache.jena:jena-fuseki-server
ARG JENA_VERSION=6.2.0
# Temurin 21 JRE — Jena 6.x targets Java 21. (Renovate datasource TBD; bump with Jena.)
ARG TEMURIN_VERSION=21.0.5+11
# renovate: datasource=github-releases depName=babashka/babashka extractVersion=^v(?<version>.*)$
ARG BB_VERSION=1.12.216

# ---------------------------------------------------------------------------
# Stage 1: fetch. Runs NATIVELY on the build machine (--platform=$BUILDPLATFORM)
# and selects artifacts for $TARGETARCH.
#
# Why: every download and untar here used to run inside the target image, which
# for linux/arm64 on an amd64 CI runner means QEMU emulation. Emulated curl
# pulling ~200MB (JRE + Fuseki + babashka) is slow and flaky — it failed two
# publishes in a row, `exit 22` (HTTP error) and `exit 56` (connection reset
# mid-transfer), and `--retry` didn't save the second one. Nothing here needs to
# EXECUTE target-arch code; it only needs to unpack it. So fetch natively and
# copy the bytes across.
# ---------------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM debian:bookworm-slim AS fetch
ARG JENA_VERSION
ARG TEMURIN_VERSION
ARG BB_VERSION
ARG TARGETARCH

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
 && rm -rf /var/lib/apt/lists/*

# The key Apache Jena releases are signed with. PINNED, and the pin is the whole
# point: `gpg --verify` exits 0 for a good signature from ANY key it happens to
# hold, so importing KEYS and verifying proves only that *someone* signed it —
# and whoever can serve you a tarball can serve you a KEYS file to check it
# against. Asserting the fingerprint is what turns "signed" into "signed by Jena".
#
# D99038A1731B8B31B71549EF04C95136D236A58F is Andy Seaborne <andy@apache.org>,
# "Code signing key", present in https://downloads.apache.org/jena/KEYS and the
# issuer recorded in the .asc for 6.2.0.
#
# A Jena release cut by a different release manager WILL fail this check. That is
# deliberate: trusting a new signer should be a person's decision, not a silent
# consequence of a version bump. Update this in the same commit as JENA_VERSION.
ARG JENA_KEY_FPR=D99038A1731B8B31B71549EF04C95136D236A58F

# Adoptium's signing key, same reasoning. 3B04D753... is
# "Adoptium GPG Key (DEB/RPM Signing Key) <temurin-dev@eclipse.org>", the issuer
# of the .sig for the pinned JRE.
#
# Its public key comes from a KEYSERVER rather than from the release host, which
# is strictly better than the Apache case above: the key and the artifact then
# travel different channels, so compromising the download host is not enough.
ARG TEMURIN_KEY_FPR=3B04D753C9050D9A5D343F39843C48A565F8F04B

# Patient, exponential backoff — NOT a fixed short delay. GitHub rate-limits
# unauthenticated release downloads, and a run fetches these once per target
# platform per job, so the limit is reachable. `--retry 5 --retry-delay 3` spans
# only ~15s and lost to a 503; no --retry-delay means curl backs off
# exponentially, and --retry-max-time lets it ride out a throttle window.
ENV CURL="curl -fsSL --retry 8 --retry-max-time 300 --retry-all-errors --connect-timeout 20"

# Temurin JRE 21 for the TARGET arch.
RUN set -eux; \
    case "$TARGETARCH" in \
      amd64) JRE_ARCH=x64 ;; \
      arm64) JRE_ARCH=aarch64 ;; \
      *) echo "unsupported TARGETARCH: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    V="$TEMURIN_VERSION"; \
    TAG="jdk-$(echo "$V" | sed 's/+/%2B/')"; \
    FILE="OpenJDK21U-jre_${JRE_ARCH}_linux_hotspot_$(echo "$V" | sed 's/+/_/').tar.gz"; \
    $CURL "https://github.com/adoptium/temurin21-binaries/releases/download/${TAG}/${FILE}" -o /tmp/jre.tgz; \
    $CURL "https://github.com/adoptium/temurin21-binaries/releases/download/${TAG}/${FILE}.sha256.txt" -o /tmp/jre.sha256; \
    echo "$(cut -d' ' -f1 /tmp/jre.sha256)  /tmp/jre.tgz" | sha256sum -c -; \
    # Provenance, same shape as Fuseki below: pinned fingerprint, gpg's status
    # stream rather than its exit code. The key comes from a keyserver, so it and
    # the artifact do not share a host.
    $CURL "https://github.com/adoptium/temurin21-binaries/releases/download/${TAG}/${FILE}.sig" -o /tmp/jre.sig; \
    $CURL "https://keyserver.ubuntu.com/pks/lookup?op=get&options=mr&search=0x${TEMURIN_KEY_FPR}" -o /tmp/temurin-key.asc; \
    export GNUPGHOME=/tmp/gnupg-jre; mkdir -m 700 -p "$GNUPGHOME"; \
    gpg --batch --quiet --import /tmp/temurin-key.asc; \
    gpg --batch --status-fd 1 --verify /tmp/jre.sig /tmp/jre.tgz \
      | tee /tmp/jre.status | grep -q "^\[GNUPG:\] VALIDSIG ${TEMURIN_KEY_FPR} " \
      || { echo "FATAL: JRE .sig is not a valid signature from ${TEMURIN_KEY_FPR}" >&2; \
           cat /tmp/jre.status >&2; exit 1; }; \
    echo "provenance: JRE signature verified against pinned key ${TEMURIN_KEY_FPR}"; \
    mkdir -p /out/jre; tar -xzf /tmp/jre.tgz -C /out/jre --strip-components=1; \
    rm -rf /tmp/jre.tgz /tmp/jre.sha256 /tmp/jre.sig /tmp/temurin-key.asc /tmp/jre.status "$GNUPGHOME"

# Apache Jena Fuseki (standalone, arch-independent). archive.apache.org is
# permanent — dlcdn is latest-only and 404s a pinned version once a newer Jena ships.
#
# EVERY artifact fetched here is checksum-verified. It used to be only babashka,
# with a comment explaining why — reasoning that applied at least as strongly to
# the ~200MB JRE, and to a tarball whose only check was `test -f` on a path a
# truncated archive can still produce. This stage's own header records two
# publishes lost to `exit 56`, connection reset MID-TRANSFER: exactly the failure a
# checksum catches and --retry does not. test/dockerfile_test.clj now fails if a
# future artifact is added without one.
#
# INTEGRITY AND PROVENANCE, and they are different claims.
#
# The sha512 says the bytes arrived intact. It cannot say they are Apache's,
# because the checksum travels the same channel as the artifact — anyone who can
# alter one can alter the other. The .asc signature is the provenance claim, and
# it only means something because JENA_KEY_FPR above is pinned.
#
# `--status-fd 1` plus a grep for VALIDSIG <fpr> is used rather than gpg's exit
# code on purpose: gpg exits 0 for a good signature from an untrusted key, so
# `gpg --verify && echo ok` would pass for a tarball signed by anybody at all.
RUN set -eux; \
    BASE="https://archive.apache.org/dist/jena/binaries/apache-jena-fuseki-${JENA_VERSION}.tar.gz"; \
    $CURL "$BASE" -o /tmp/fuseki.tgz; \
    # Apache publishes .sha512 here, not .sha256 (that URL 404s).
    $CURL "${BASE}.sha512" -o /tmp/fuseki.sha512; \
    echo "$(cut -d' ' -f1 /tmp/fuseki.sha512)  /tmp/fuseki.tgz" | sha512sum -c -; \
    $CURL "${BASE}.asc" -o /tmp/fuseki.asc; \
    $CURL "https://downloads.apache.org/jena/KEYS" -o /tmp/jena-KEYS; \
    export GNUPGHOME=/tmp/gnupg; mkdir -m 700 -p "$GNUPGHOME"; \
    gpg --batch --quiet --import /tmp/jena-KEYS; \
    gpg --batch --status-fd 1 --verify /tmp/fuseki.asc /tmp/fuseki.tgz \
      | tee /tmp/gpg.status | grep -q "^\[GNUPG:\] VALIDSIG ${JENA_KEY_FPR} " \
      || { echo "FATAL: Fuseki .asc is not a valid signature from ${JENA_KEY_FPR}" >&2; \
           cat /tmp/gpg.status >&2; exit 1; }; \
    echo "provenance: signature verified against pinned key ${JENA_KEY_FPR}"; \
    mkdir -p /out/fuseki; tar -xzf /tmp/fuseki.tgz -C /out/fuseki --strip-components=1; \
    rm -rf /tmp/fuseki.tgz /tmp/fuseki.sha512 /tmp/fuseki.asc /tmp/jena-KEYS /tmp/gpg.status "$GNUPGHOME"; \
    test -f /out/fuseki/fuseki-server.jar

# babashka — the entrypoint runtime (arch-specific static binary).
#
# NOTE the format difference: bb publishes a BARE hash, so it pairs directly.
# Jena and Temurin publish "hash␣␣filename" lines, so those two `cut` the hash out
# and re-pair it against the temp path — see above.
RUN set -eux; \
    case "$TARGETARCH" in \
      amd64) BB_ARCH=amd64 ;; \
      arm64) BB_ARCH=aarch64 ;; \
      *) echo "unsupported TARGETARCH: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    BASE="https://github.com/babashka/babashka/releases/download/v${BB_VERSION}/babashka-${BB_VERSION}-linux-${BB_ARCH}-static.tar.gz"; \
    $CURL "$BASE" -o /tmp/bb.tgz; \
    $CURL "${BASE}.sha256" -o /tmp/bb.sha256; \
    echo "$(cat /tmp/bb.sha256)  /tmp/bb.tgz" | sha256sum -c -; \
    mkdir -p /out/bin; tar -xzf /tmp/bb.tgz -C /out/bin bb; rm /tmp/bb.tgz /tmp/bb.sha256

# ---------------------------------------------------------------------------
# Stage 2: the image itself, for $TARGETPLATFORM.
# ---------------------------------------------------------------------------
FROM debian:bookworm-slim

# curl stays in the final image — the HEALTHCHECK uses it.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*

COPY --from=fetch /out/jre    /opt/jre
COPY --from=fetch /out/fuseki /opt/fuseki
COPY --from=fetch /out/bin/bb /usr/local/bin/bb

ENV JAVA_HOME=/opt/jre
ENV PATH="/opt/jre/bin:${PATH}"

# The whole entrypoint tree: entrypoint.clj (orchestration) + sp_fuseki/render.clj
# (pure rendering, unit-tested by test/render_test.clj).
COPY entrypoint/ /opt/sp-fuseki/

ENV FUSEKI_BASE=/fuseki/run
ENV FUSEKI_JAR=/opt/fuseki/fuseki-server.jar
# So `(require '[sp-fuseki.render])` resolves without passing --classpath here
# and in every ad-hoc `bb` invocation inside the container.
ENV BABASHKA_CLASSPATH=/opt/sp-fuseki

# Non-root. The data/runtime dirs must be writable by the runtime user.
#
# /fuseki/databases exists in the image ON PURPOSE, even though it's empty: a
# named volume mounted onto an existing path inherits that path's ownership, so
# `-v vol:/fuseki/databases` just works as uid 1000. Mount onto a path that
# ISN'T in the image and Docker creates it root-owned — TDB2 then dies with
# "IOException: No such file or directory", which looks like a config error and
# isn't. See README → Persistence.
#
# /fuseki/run/configuration is pre-created for exactly the same reason, and it
# bites harder because the path is one Fuseki tells you to use: mounting a single
# file into it (`-v ./ds.ttl:/fuseki/run/configuration/ds.ttl`) makes Docker
# create the MISSING PARENT root-owned, and Fuseki then dies "Not writable"
# before it has served anything. Creating it here costs an empty directory.
RUN useradd --uid 1000 --create-home --shell /usr/sbin/nologin fuseki \
 && mkdir -p /fuseki/run/configuration /fuseki/databases /fuseki/pre-start.d \
 && chown -R fuseki:fuseki /fuseki

# Both binaries must actually run on THIS arch — the one thing the fetch stage
# can't check, since it runs on the build platform.
RUN set -eux; bb --version; java -version

USER fuseki
WORKDIR /fuseki

EXPOSE 3030
# Reads the port the entrypoint ACTUALLY resolved, from the file it writes under
# FUSEKI_BASE, falling back to the env var and then the default. The port can come
# from fuseki.edn's :server {:port n}, and a healthcheck that only knew
# FUSEKI_PORT would report such a container unhealthy while it served happily.
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
  CMD ["sh", "-c", "p=$(cat \"${FUSEKI_BASE:-/fuseki/run}/port\" 2>/dev/null || echo \"${FUSEKI_PORT:-3030}\"); curl -fsS \"http://localhost:${p}/\\$/ping\" || exit 1"]

ENTRYPOINT ["bb", "/opt/sp-fuseki/entrypoint.clj"]
