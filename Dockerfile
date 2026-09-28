# clojure-pds: AT Protocol PDS on PostgreSQL. The image runs from source with
# a prebuilt dependency cache; the committed account UI needs no Node here.
ARG JDK_IMAGE="eclipse-temurin:25-jdk-noble"
ARG RUNNER_IMAGE="eclipse-temurin:25-jre-noble"
ARG CLOJURE_CLI_VERSION="1.12.4.1618"

FROM ${JDK_IMAGE} AS builder
ARG CLOJURE_CLI_VERSION

RUN apt-get update -y \
  && apt-get install -y --no-install-recommends bash ca-certificates curl git \
  && rm -rf /var/lib/apt/lists/* \
  && curl -fsSL "https://github.com/clojure/brew-install/releases/download/${CLOJURE_CLI_VERSION}/linux-install.sh" -o /tmp/clojure-install.sh \
  && bash /tmp/clojure-install.sh \
  && rm /tmp/clojure-install.sh

WORKDIR /app
ENV GITLIBS="/app/.gitlibs" \
    CLJ_CACHE="/app/.cpcache"

COPY deps.edn ./
# The cache directories are created up front: with no git dependencies the
# resolver never creates GITLIBS, and the runner stage copies both paths.
RUN mkdir -p "$GITLIBS" "$CLJ_CACHE" \
  && clojure -P && clojure -P -M:run && clojure -P -M:migrate

COPY resources resources
COPY src src
RUN clojure -P -M:run

FROM ${RUNNER_IMAGE} AS runner
ARG CLOJURE_CLI_VERSION

RUN apt-get update -y \
  && apt-get install -y --no-install-recommends bash ca-certificates curl \
  && rm -rf /var/lib/apt/lists/* \
  && curl -fsSL "https://github.com/clojure/brew-install/releases/download/${CLOJURE_CLI_VERSION}/linux-install.sh" -o /tmp/clojure-install.sh \
  && bash /tmp/clojure-install.sh \
  && rm /tmp/clojure-install.sh

WORKDIR /app

COPY --from=builder /root/.m2 /home/pds/.m2
COPY --from=builder /app/.gitlibs /app/.gitlibs
COPY --from=builder /app/.cpcache /app/.cpcache
COPY deps.edn ./
COPY resources resources
COPY src src

RUN useradd --system --home /home/pds --shell /usr/sbin/nologin pds \
  && chown -R pds:pds /home/pds /app

USER pds
ENV HOME="/home/pds" \
    GITLIBS="/app/.gitlibs" \
    CLJ_CACHE="/app/.cpcache" \
    PDS_HOST="0.0.0.0" \
    PDS_PORT="3000"

EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${PDS_PORT}/xrpc/_health" >/dev/null

# Startup runs checksummed migrations under an advisory lock before binding.
CMD ["clojure", "-M:run"]
