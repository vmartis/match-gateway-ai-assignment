# syntax=docker/dockerfile:1
# One build, one image per deployable (ADR-001): docker build --target gateway|matcher .
# Base images pinned by digest (ARCHITECTURE T6); bump tag + digest together.

FROM eclipse-temurin:25-jdk@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY common common
COPY gateway gateway
COPY matcher matcher
# bootJar does not depend on the test task; tests run in ./scripts/verify.sh (they need Docker for Testcontainers).
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain :gateway:bootJar :matcher:bootJar

FROM eclipse-temurin:25-jre@sha256:fcd7fd7b387f94bb2ac461478a7436ad8e349924c374ea8313919624dceae636 AS runtime
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

FROM runtime AS gateway
# Root-owned and read-only for uid 10001: the app cannot rewrite its own code.
COPY --from=build /src/gateway/build/libs/app.jar /app/app.jar
EXPOSE 8080 8081

FROM runtime AS matcher
COPY --from=build /src/matcher/build/libs/app.jar /app/app.jar
EXPOSE 8081
