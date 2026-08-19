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
uv run hypercorn app.main:app --bind 127.0.0.1:8000 --reload
```

AI Service는 Hypercorn으로 실행한다. Hypercorn은 Backend Java Client의 HTTP/2 h2c 업그레이드를
처리하며, HTTP/1.1 요청도 함께 지원한다.

Docker private network에서는 AI 컨테이너 내부에서만 모든 인터페이스에 바인딩한다. AI 컨테이너의
호스트 포트는 publish하지 않고, 같은 Docker network의 Backend만 서비스 이름으로 연결한다.

```bash
# AI 컨테이너 내부 실행 명령
uv run hypercorn app.main:app --bind 0.0.0.0:8000
```

```text
AI_SERVICE_URL=http://ai-service:8000
```

여기서 `ai-service`는 Compose service 이름이다. 별도 Backend 컨테이너에서 `127.0.0.1:8000`은
Backend 자신을 가리키므로 사용하면 안 된다.

| 주소 | 설명 |
| --- | --- |
| http://localhost:8000/health | Health Check |
| http://localhost:8000/docs | Swagger UI |

`APP_ENV=prod`에서는 Swagger UI, ReDoc, OpenAPI JSON을 비활성화한다.

## 내부 보고서 생성 API

`POST /internal/ai/reports/generate`는 Backend 전용 multipart API다. `X-Internal-Token`이
설정된 `AI_INTERNAL_TOKEN`과 일치하지 않으면 401 `AI_UNAUTHORIZED`로 거부한다. Backend가
생성한 bundle을 다음 part로 전달한다.

| Part | Content-Type | 내용 |
| --- | --- | --- |
| `request` | `application/json` | `fileIds`, `metadata`, `instructions` |
| `manifest` | `application/json` | bundle 파일 메타데이터 |
| `files` | 파일 MIME | manifest 순서와 일치하는 반복 파일 part |

AI Service는 manifest와 `files`의 개수·순서·파일명·MIME·크기를 검증한다.
`MOCK_REPORT=true`에서는 유효한 bundle을 받으면 샘플 `ReportDocument`를 반환한다.
`false`이면 Gemini를 통해 요구사항·소스·이미지를 단계별로 분석하고,
`ReportDocument`를 생성·검증한다. 상세 계약은
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
| `MOCK_REPORT` | `true` | true면 Gemini 호출 없이 샘플 보고서를 반환 (운영에서는 false 고정) |
| `AI_INTERNAL_TOKEN` | 없음 | Backend 내부 생성 요청 인증용 공유 Secret (운영 필수) |
| `GEMINI_MODEL` | `gemini-3.5-flash-lite` | 무료 티어 우선 모델. 다른 모델은 설정 검증에서 거부 |
| `GEMINI_API_KEY` | 없음 | AI Service가 Gemini 호출에 사용하는 서버 키 (운영 필수) |
| `GEMINI_TIMEOUT_SECONDS` | `300` | Gemini 호출 타임아웃 |
| `GEMINI_MAX_RETRIES` | `2` | 스키마 검증 실패 시 재시도 횟수 |

## API Key 취급 원칙

- MVP는 AI Service 서버 환경변수의 `GEMINI_API_KEY`를 사용한다.
- 사용자별 API Key는 MVP 범위에서 제외하며 필요성이 확인되면 확장한다.
- 로그와 예외 메시지에 키를 남기지 않는다 (`app/core/logging.py`의 마스킹 필터).
- 파일이나 DB에 저장하지 않는다.
- 코드에서는 `gemini-3.5-flash-lite`만 허용한다. 무료 티어는 모델별 요청·토큰 한도를 넘으면
  `429 RESOURCE_EXHAUSTED`를 반환한다.
- 이 서비스는 현재 Gemini **무료 티어만** 사용한다. 무료 티어에서는 전송한 문서·코드·이미지와
  생성 결과가 Gemini 제품 개선에 사용될 수 있으므로, 사용자는 자료 소유자에게 이를 고지하고 동의를
  받아야 한다. 비밀키·인증서·개인정보·기밀 자료는 업로드하지 않는다.
- Free Tier 데이터 처리 조건은 지역별로 다를 수 있으므로 배포 전에
  [Gemini API 약관](https://ai.google.dev/gemini-api/terms)과
  [가격·데이터 사용 정책](https://ai.google.dev/gemini-api/docs/pricing)을 확인한다.
- 일반 **alerts-only Google Cloud Budget**은 사용량 또는 과금을 멈추지 않고 알림만 보낸다.
  과금 프로젝트를 운영한다면 Gemini API가 대상인 **Spend cap** 또는 AI Studio의 프로젝트별
  월간 spend cap을 설정한다. spend cap은 처리 지연으로 소폭 초과 과금될 수 있으므로, 애플리케이션의
  동시 생성 제한과 Gemini API rate limit도 함께 적용한다.

운영에서는 `APP_ENV=prod`, `MOCK_REPORT=false`, `AI_INTERNAL_TOKEN`, `GEMINI_API_KEY`를
모두 주입한다. 하나라도 없거나 Mock이 켜져 있으면 프로세스가 기동하지 않는다. AI Service는
Backend와 같은 서버 또는 Docker private network에만 바인딩하고 public port를 열지 않는다.

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
- [x] `X-Internal-Token` AI Service 검증 추가 (배포 전 보안 task)
- [x] Gemini Client 및 생성 파이프라인 (요구사항 → 소스 → 이미지 → 목차 → 문서 생성)
