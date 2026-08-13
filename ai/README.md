# AI Service

DevReport AI의 문서·코드·이미지 분석 및 보고서 생성 서비스.

Backend 전용 **내부 서비스**다. Frontend나 외부 사용자가 직접 호출하지 않는다.

```text
Frontend → Spring Backend → FastAPI AI Service → Gemini API
```

## 기술 스택

| 항목 | 선택 |
| --- | --- |
| 언어 | Python 3.11 |
| 프레임워크 | FastAPI |
| 패키지 관리 | uv (`pyproject.toml` + `uv.lock`) |
| 검증 | Pydantic v2, jsonschema |
| LLM SDK | google-genai (Gemini) |
| Lint / Format | Ruff |
| 테스트 | pytest |

## 실행 방법

### 1. uv 설치 (최초 1회)

```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
```

설치 후 셸을 다시 열거나 `export PATH="$HOME/.local/bin:$PATH"`를 적용한다.

### 2. 의존성 설치

```bash
cd ai
uv sync
```

`uv.lock`에 고정된 버전으로 `.venv`를 만든다. Python 3.11이 없으면 uv가 자동으로 받아온다.

### 3. 환경변수 설정

```bash
cp .env.example .env
```

### 4. 서버 실행

```bash
uv run uvicorn app.main:app --reload --port 8000
```

| 주소 | 설명 |
| --- | --- |
| http://localhost:8000/health | Health Check |
| http://localhost:8000/docs | Swagger UI |

## 내부 보고서 생성 API

`POST /internal/ai/reports/generate`는 Backend 전용 multipart API다. Backend가 생성한
bundle을 다음 part로 전달한다.

| Part | Content-Type | 내용 |
| --- | --- | --- |
| `request` | `application/json` | `fileIds`, `metadata`, `instructions` |
| `manifest` | `application/json` | bundle 파일 메타데이터 |
| `files` | 파일 MIME | manifest 순서와 일치하는 반복 파일 part |

AI Service는 manifest와 `files`의 개수·순서·파일명·MIME·크기를 검증한다. 현재
`MOCK_REPORT=true`에서는 유효한 bundle을 받으면 샘플 `ReportDocument`를 반환하며,
실제 문서·코드·이미지 분석과 Gemini 호출은 후속 작업에서 추가한다. 상세 계약은
[`contracts/report-generation.md`](../contracts/report-generation.md)를 따른다.

생성 API의 multipart 본문은 ASGI 수신 단계에서 최대 100 MiB로 제한한다. `Content-Length`
요청은 파싱 전에 즉시 거부하며, chunked 요청도 수신 바이트가 한도를 넘는 즉시 거부한다.

### 5. 테스트 및 린트

```bash
uv run pytest
uv run ruff check .
uv run ruff format .
```

## 환경변수

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `APP_ENV` | `local` | 실행 환경 이름 |
| `LOG_LEVEL` | `INFO` | 로그 레벨 |
| `CONTRACTS_DIR` | 저장소 `contracts/` | 공통 계약 디렉터리 경로 |
| `MOCK_REPORT` | `true` | true면 Gemini 호출 없이 샘플 보고서를 반환 |
| `AI_INTERNAL_TOKEN` | 없음 | Backend 내부 생성 요청 인증용 공유 Secret |
| `GEMINI_MODEL` | `gemini-2.5-pro` | 사용할 Gemini 모델 |
| `GEMINI_API_KEY` | 없음 | AI Service가 Gemini 호출에 사용하는 서버 키 |
| `GEMINI_TIMEOUT_SECONDS` | `300` | Gemini 호출 타임아웃 |
| `GEMINI_MAX_RETRIES` | `2` | 스키마 검증 실패 시 재시도 횟수 |

## API Key 취급 원칙

- MVP는 AI Service 서버 환경변수의 `GEMINI_API_KEY`를 사용한다.
- 사용자별 API Key는 MVP 범위에서 제외하며 필요성이 확인되면 확장한다.
- 로그와 예외 메시지에 키를 남기지 않는다 (`app/core/logging.py`의 마스킹 필터).
- 파일이나 DB에 저장하지 않는다.

## 디렉터리 구조

```text
ai/
├── app/
│   ├── main.py          FastAPI 앱 생성
│   ├── api/             라우터
│   └── core/            설정, 로깅, 공통 에러
├── tests/
├── pyproject.toml
└── uv.lock
```

## 다음 작업

- [x] `POST /internal/ai/reports/generate` multipart bundle Mock 구현
- [ ] `X-Internal-Token` AI Service 검증 추가 (배포 전 보안 task)
- [ ] Gemini Client 및 생성 파이프라인 (추출 → 목차 설계 → 섹션 생성)
