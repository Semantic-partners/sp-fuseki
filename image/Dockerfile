# syntax=docker/dockerfile:1
#
# sp-fuseki — clean, config-respecting Apache Jena Fuseki.
# Built from the pinned Jena dist (archive.apache.org — permanent), Temurin 21,
# babashka entrypoint. Multi-arch (amd64 + arm64), non-root, healthchecked.

# --- versions (Renovate-managed) ---
# renovate: datasource=maven depName=org.apache.jena:jena-fuseki-server
ARG JENA_VERSION=6.1.0
# Temurin 21 JRE — Jena 6.x targets Java 21. (Renovate datasource TBD; bump with Jena.)
ARG TEMURIN_VERSION=21.0.5+11
# renovate: datasource=github-releases depName=babashka/babashka extractVersion=^v(?<version>.*)$
ARG BB_VERSION=1.12.216

FROM debian:bookworm-slim
ARG JENA_VERSION
ARG TEMURIN_VERSION
ARG BB_VERSION
ARG TARGETARCH

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*

# Temurin JRE 21. TARGETARCH is set by buildx (amd64 on CI, arm64 on Apple Silicon).
RUN set -eux; \
    case "$TARGETARCH" in \
      amd64) JRE_ARCH=x64 ;; \
      arm64) JRE_ARCH=aarch64 ;; \
      *) echo "unsupported TARGETARCH: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    V="$TEMURIN_VERSION"; \
    TAG="jdk-$(echo "$V" | sed 's/+/%2B/')"; \
    FILE="OpenJDK21U-jre_${JRE_ARCH}_linux_hotspot_$(echo "$V" | sed 's/+/_/').tar.gz"; \
    curl -fsSL "https://github.com/adoptium/temurin21-binaries/releases/download/${TAG}/${FILE}" -o /tmp/jre.tgz; \
    mkdir -p /opt/jre; tar -xzf /tmp/jre.tgz -C /opt/jre --strip-components=1; rm /tmp/jre.tgz
ENV JAVA_HOME=/opt/jre
ENV PATH="/opt/jre/bin:${PATH}"

# Apache Jena Fuseki (standalone). archive.apache.org is permanent — dlcdn is
# latest-only and 404s a pinned version once a newer Jena ships.
RUN set -eux; \
    curl -fsSL "https://archive.apache.org/dist/jena/binaries/apache-jena-fuseki-${JENA_VERSION}.tar.gz" -o /tmp/fuseki.tgz; \
    mkdir -p /opt/fuseki; tar -xzf /tmp/fuseki.tgz -C /opt/fuseki --strip-components=1; rm /tmp/fuseki.tgz

# babashka — the entrypoint runtime (arch-specific static binary).
RUN set -eux; \
    case "$TARGETARCH" in \
      amd64) BB_ARCH=amd64 ;; \
      arm64) BB_ARCH=aarch64 ;; \
      *) echo "unsupported TARGETARCH: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    curl -fsSL "https://github.com/babashka/babashka/releases/download/v${BB_VERSION}/babashka-${BB_VERSION}-linux-${BB_ARCH}-static.tar.gz" -o /tmp/bb.tgz; \
    tar -xzf /tmp/bb.tgz -C /usr/local/bin bb; rm /tmp/bb.tgz; \
    bb --version

COPY entrypoint/entrypoint.clj /opt/sp-fuseki/entrypoint.clj

ENV FUSEKI_BASE=/fuseki/run
ENV FUSEKI_JAR=/opt/fuseki/fuseki-server.jar

# Non-root. The data/runtime dir must be writable by the runtime user.
RUN useradd --uid 1000 --create-home --shell /usr/sbin/nologin fuseki \
 && mkdir -p /fuseki/run \
 && chown -R fuseki:fuseki /fuseki
USER fuseki
WORKDIR /fuseki

EXPOSE 3030
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
  CMD ["sh", "-c", "curl -fsS \"http://localhost:${FUSEKI_PORT:-3030}/\\$/ping\" || exit 1"]

ENTRYPOINT ["bb", "/opt/sp-fuseki/entrypoint.clj"]
