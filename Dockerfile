FROM maven:3.9.15-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN apk upgrade --no-cache \
    && addgroup -S -g 10001 pfi \
    && adduser -S -D -H -u 10001 -G pfi pfi \
    && mkdir -p /app/storage/object_storage \
    && chown -R 10001:10001 /app
COPY --from=build --chown=10001:10001 /app/target/*.jar app.jar
COPY --chown=10001:10001 docs/migrations ./docs/migrations

ENV PORT=8080
ENV PFI_AI_SERVICE_URL=http://host.docker.internal:8000
ENV PFI_AI_TIMEOUT_SECONDS=180
ENV PFI_PERSISTENCE_MODE=memory
ENV PFI_STORAGE_PROVIDER=filesystem
ENV PFI_STORAGE_LOCAL_ROOT=/app/storage/object_storage

EXPOSE 8080
USER 10001:10001
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- "http://127.0.0.1:${PORT:-8080}/api/system/health" >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
