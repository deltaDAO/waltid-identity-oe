# Build the wallet API image from this repository (run from the repository root):
#   docker build -f waltid-services/waltid-wallet-api/Dockerfile -t deltadao/wallet-api:<tag> .
# Uses the Gradle wrapper, so the Gradle version always matches the repository.

# Stage 1: Build the fat JAR
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
# Enough heap for the large monorepo build, without a long-running daemon
ENV GRADLE_OPTS="-Xmx4g -Xms512m"
RUN chmod +x gradlew && \
    ./gradlew :waltid-services:waltid-wallet-api:buildFatJar --no-daemon

# Stage 2: Runtime image
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /waltid-wallet-api

RUN groupadd -r app && useradd -r -g app -u 10001 app && \
    mkdir -p /waltid-wallet-api/data && chown -R app:app /waltid-wallet-api

COPY --chown=app:app --from=build /src/waltid-services/waltid-wallet-api/build/libs/*-all.jar /waltid-wallet-api/wallet-api.jar

# Configuration is mounted at /waltid-wallet-api/config (see docker-compose)
USER app
EXPOSE 7001
ENTRYPOINT ["java", "-jar", "/waltid-wallet-api/wallet-api.jar"]
