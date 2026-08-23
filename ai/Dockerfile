# syntax=docker/dockerfile:1

# ── build ────────────────────────────────────────────────────────────────
FROM python:3.11-slim-bookworm AS build

# uv.lock에 고정된 버전으로만 설치해 로컬과 서버의 의존성을 일치시킨다.
COPY --from=ghcr.io/astral-sh/uv:0.9.7 /uv /usr/local/bin/uv

WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PROJECT_ENVIRONMENT=/app/.venv

COPY ai/pyproject.toml ai/uv.lock ./
RUN uv sync --locked --no-dev --no-install-project

# ── runtime ──────────────────────────────────────────────────────────────
FROM python:3.11-slim-bookworm AS runtime

RUN useradd --create-home --shell /usr/sbin/nologin devreport

WORKDIR /app
COPY --from=build /app/.venv /app/.venv
COPY ai/app/ app/

# contracts는 ReportDocument 검증에 필요하다. CONTRACTS_DIR로 이 경로를 가리킨다.
COPY contracts/ /contracts/

ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    CONTRACTS_DIR=/contracts

USER devreport
EXPOSE 8000

# Backend Java Client의 h2c 업그레이드를 처리하기 위해 Hypercorn을 쓴다.
# 1 vCPU이므로 worker는 1로 둔다.
CMD ["hypercorn", "app.main:app", "--bind", "0.0.0.0:8000", "--workers", "1"]
