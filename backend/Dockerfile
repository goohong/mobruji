# syntax=docker/dockerfile:1.6
# Mobruji backend (Spring Boot 3.5.3, Java 21).
# Phase 4 NCP dev 배포용 — docs/features/ncp-dev-deployment.md §5-2.
#
# 빌드:
#   docker build --build-arg GIT_SHA=$(git rev-parse --short HEAD) -t mobruji/backend:dev backend/
# 실행 (compose):
#   docker compose -f docker-compose.dev.yml up -d backend

#######################################
# Stage 1 — builder
#######################################
FROM eclipse-temurin:21-jdk-noble AS builder

WORKDIR /workspace

# Gradle wrapper / 설정 먼저 복사 — 의존성 캐시 레이어 분리.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle

# 의존성 사전 다운로드 (캐시 hit 가능성 ↑).
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# 소스 + checkstyle/spotless 설정 복사.
COPY src ./src
COPY config ./config

# bootJar 생성. test 는 CI 에서 검증되므로 빌드 시 skip.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar -x test

#######################################
# Stage 2 — runner
#######################################
FROM eclipse-temurin:21-jre-noble AS runner

# 보안: non-root user. UID/GID 1001 — eclipse-temurin:21-jre-noble 의 ubuntu user 가
# UID 1000 을 이미 점유해서 1000 사용 시 useradd 실패 (groupadd: GID '1000' already exists).
# web/Dockerfile (nextjs:nodejs) 와 동일 UID 사용으로 일관성.
RUN groupadd --system --gid 1001 spring \
    && useradd --system --uid 1001 --gid spring --no-create-home spring

# 헬스체크용 curl. 이미지 크기 +5MB 이내.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /workspace/build/libs/*.jar /app/app.jar

# 빌드 메타데이터 — /actuator/info 에서도 노출 가능.
ARG GIT_SHA=unknown
ENV GIT_SHA=${GIT_SHA}

USER spring:spring

# JVM 메모리 — docker-compose.dev.yml 의 memory limit 600M 에 맞춤.
# 100MB JVM/native overhead 여유로 두고 heap 은 -Xmx512m.
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=80.0"

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=4 \
    CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
