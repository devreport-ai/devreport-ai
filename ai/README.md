# AI Service

DevReport AI의 문서·코드·이미지 분석 및 보고서 생성 서비스.

Backend 전용 **내부 서비스**다. Frontend나 외부 사용자가 직접 호출하지 않는다.

```
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
| `GEMINI_MODEL` | `gemini-2.5-pro` | 사용할 Gemini 모델 |
| `GEMINI_API_KEY` | 없음 | 로컬 개발용 fallback 키 |
| `GEMINI_TIMEOUT_SECONDS` | `300` | Gemini 호출 타임아웃 |
| `GEMINI_MAX_RETRIES` | `2` | 스키마 검증 실패 시 재시도 횟수 |

## API Key 취급 원칙

`docs`의 13번 규칙을 그대로 따른다.

- 사용자 Gemini API Key는 **요청 단위**로 Backend에서 전달받는다.
- 전역 클라이언트에 키를 보관하지 않는다.
- 로그와 예외 메시지에 키를 남기지 않는다 (`app/core/logging.py`의 마스킹 필터).
- 파일이나 DB에 저장하지 않는다.

## 디렉터리 구조

```
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

- [ ] `contracts/report-document.schema.json` 보강 (3인 리뷰 필요)
- [ ] `POST /internal/ai/reports/generate` 임시 구현 (샘플 ReportDocument 반환)
- [ ] Gemini Client 및 생성 파이프라인 (추출 → 목차 설계 → 섹션 생성)
