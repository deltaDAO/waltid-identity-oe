# Build the wallet API image from this repository (run from the repository root):
#   docker build -f waltid-services/waltid-wallet-api/Dockerfile -t deltadao/wallet-api:<tag> .
# Uses the Gradle wrapper, so the Gradle version always matches the repository.
# Built with installDist (separate library jars, like the Jib images): a fat jar would overwrite
# hoplite's service files and the wallet could no longer read its .conf configuration.

# Stage 1: Build the application distribution
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
# Enough heap for the large monorepo build, without a long-running daemon
ENV GRADLE_OPTS="-Xmx4g -Xms512m"
RUN chmod +x gradlew && \
    ./gradlew :waltid-services:waltid-wallet-api:installDist --no-daemon

# Stage 2: Runtime image
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /waltid-wallet-api

RUN groupadd -r app && useradd -r -g app -u 10001 app && \
    mkdir -p /waltid-wallet-api/data && chown -R app:app /waltid-wallet-api

COPY --from=build /src/waltid-services/waltid-wallet-api/build/install/waltid-wallet-api /opt/wallet-api

# Configuration is mounted at /waltid-wallet-api/config (see docker-compose)
USER app
EXPOSE 7001
# Runs with /waltid-wallet-api as working directory, so config/ and data/ are found there
ENTRYPOINT ["/opt/wallet-api/bin/waltid-wallet-api"]
