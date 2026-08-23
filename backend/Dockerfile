# syntax=docker/dockerfile:1

# ── build ────────────────────────────────────────────────────────────────
# Gradle 빌드와 Chromium 다운로드는 서버(1 vCPU)에서 돌리면 너무 느리므로
# 개발 머신에서 빌드해 이미지로 올린다. 서버와 같은 linux/arm64를 대상으로 한다.
FROM eclipse-temurin:21-jdk-noble AS build

WORKDIR /workspace

# contracts는 processResources가 참조하므로 함께 복사한다.
COPY contracts/ /contracts/
COPY backend/gradle/ gradle/
COPY backend/gradlew backend/build.gradle backend/settings.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet || true

COPY backend/src/ src/
COPY backend/config/ config/
RUN ./gradlew --no-daemon clean bootJar -x test

# Playwright가 내려받은 Chromium을 런타임 이미지로 옮기기 위해 경로를 고정한다.
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN ./gradlew --no-daemon installChromium

# ── runtime ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-noble AS runtime

# Chromium 실행에 필요한 공유 라이브러리. Playwright의 --with-deps가 빌드
# 단계에서 설치한 것과 같은 목록을 런타임에도 넣어야 한다.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libnss3 libnspr4 libdbus-1-3 libatk1.0-0t64 libatk-bridge2.0-0t64 \
        libcups2t64 libdrm2 libxkbcommon0 libatspi2.0-0t64 libxcomposite1 \
        libxdamage1 libxfixes3 libxrandr2 libgbm1 libpango-1.0-0 libcairo2 \
        libasound2t64 fonts-liberation fonts-noto-cjk ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

# root로 Chromium을 띄우면 sandbox 관련 문제가 생기므로 일반 사용자로 실행한다.
RUN useradd --create-home --shell /usr/sbin/nologin devreport \
    && mkdir -p /data/uploads /data/generated-reports \
    && chown -R devreport:devreport /data

# COPY 뒤에 RUN chown을 두면 복사한 파일 전체가 레이어로 한 번 더 쌓인다.
# Chromium 1GB와 fat jar 264MB가 그대로 중복되므로 --chown으로 한 번에 처리한다.
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
COPY --chown=devreport:devreport --from=build /ms-playwright /ms-playwright

WORKDIR /app
COPY --chown=devreport:devreport --from=build /workspace/build/libs/*.jar app.jar

USER devreport
EXPOSE 8080

# 1 vCPU · 컨테이너 2Gi 기준. 컨테이너 메모리를 바꾸면 이 값도 함께 조정한다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=55 -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
