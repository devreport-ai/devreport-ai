# syntax=docker/dockerfile:1

# ── build ────────────────────────────────────────────────────────────────
FROM node:22-bookworm-slim AS build

WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ ./
# Backend와 같은 Origin으로 서비스하므로 API base URL은 비워 둔다.
# 값을 넣으면 CORS 설정이 함께 필요해진다.
ENV VITE_API_BASE_URL=""
RUN npm run build

# ── runtime ──────────────────────────────────────────────────────────────
# 정적 파일 서빙과 리버스 프록시, 자동 TLS를 Caddy 하나가 담당한다.
FROM caddy:2-alpine AS runtime

COPY --from=build /app/dist /srv
COPY infra/Caddyfile /etc/caddy/Caddyfile

EXPOSE 80 443
