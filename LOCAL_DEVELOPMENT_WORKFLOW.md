# DevReport AI 로컬 개발 플로우

이 문서는 현재 작업 환경에서만 사용하는 개발 규칙이다.

- Git에 커밋하거나 PR에 포함하지 않는다.
- `.gitignore`에도 추가하지 않는다.
- 담당자가 로컬에서 직접 관리한다.

## 작업 순서

각 작업은 다음 순서를 반드시 따른다.

```text
계획 및 설계
→ 개발
→ 리뷰
→ 리팩토링
→ 리뷰
→ 완료 확인
```

리팩토링 후에도 테스트와 리뷰를 다시 수행한다. 발견한 문제는 다음 작업으로 넘기지
않고, 현재 작업 범위에서 해결하거나 명확한 후속 task로 분리한다.

## Task 운영 규칙

1. 한 번에 하나의 task만 `in_progress`로 둔다.
2. task 시작 전 목표, 변경 범위, 계약 영향, 완료 조건을 기록한다.
3. 공통 계약(`/contracts`) 변경은 개발 전에 Frontend·Backend·AI 담당자의 합의를 받는다.
4. 담당 영역 밖의 코드는 필요한 연동 목적 외에는 변경하지 않는다.
5. 테스트, lint, formatter 등 해당 task에 필요한 검증을 완료한 뒤 리뷰 단계로 이동한다.
6. 리뷰에서 나온 사항을 반영한 뒤 리팩토링과 최종 검증을 수행한다.
7. task 완료 시 변경 사항, 검증 결과, 남은 위험 또는 후속 task를 기록한다.

## 현재 Task

### AI-01 — Mock 내부 보고서 생성 API

**상태:** 완료

**목표:** 현재 Backend가 보내는 JSON 생성 요청을 받아, 유효한 샘플
`ReportDocument`를 반환하는 내부 API를 제공한다.

**변경 범위:** `ai/`만 변경한다. `/contracts`와 `/backend`는 변경하지 않는다.

**입력:** 현재 Backend의 `GenerationRequest`와 호환되는 `fileIds`, `metadata`,
`instructions` JSON 본문.

**출력:** `contracts/examples/sample-report.json`과 동일한 raw `ReportDocument`.
`sample-report-response.json`은 Backend Report API용 envelope이므로 사용하지 않는다.

**완료 조건:**

- `POST /internal/ai/reports/generate`가 유효한 요청에 200과 샘플 문서를 반환한다.
- 요청 DTO와 반환 문서는 각각 Pydantic 및 JSON Schema로 검증한다.
- 잘못된 요청은 공통 오류 응답으로 반환하며 입력 원문을 노출하지 않는다.
- API·Schema 검증 테스트와 Ruff 검증을 통과한다.

**명시적 제외:** multipart bundle, Gemini 실제 호출, 내부 토큰 강제화,
공통 오류 코드 변경. 이 항목은 Backend-AI 공동 계약 task에서 진행한다.

**리뷰 및 리팩토링 결과:**

- 현재 Backend의 `GenerationRequest` camelCase JSON(`fileIds`, `metadata`,
  `instructions`)과 호환됨을 확인했다.
- 반환값은 Report API envelope가 아닌 raw `ReportDocument`만 사용한다.
- `/contracts`, `/backend`를 변경하지 않았고, 입력값은 오류 응답에 노출되지 않는다.
- 중복 또는 불필요한 추상화가 없어 추가 리팩토링은 수행하지 않았다.

**검증:** `uv run pytest`, `uv run ruff check .`, `uv run ruff format --check .`
통과. pytest 17개 통과(Starlette TestClient deprecation warning 1건).

**다음 Task:** AI-02 — AI 내부 오류 코드 계약 정렬.

### AI-02 — AI 내부 오류 코드 계약 정렬

**상태:** 완료

**목표:** AI Service가 [contracts/report-generation.md](contracts/report-generation.md)의
내부 오류 코드만 반환하도록 정렬한다.

**변경 범위:** `ai/`와 이 로컬 규칙 파일만 변경한다. Backend의 사용자 API 오류 변환은
담당 영역이므로 변경하지 않는다.

**완료 조건:**

- 요청 검증, AI 응답 검증, 미구현 Gemini 호출, 예기치 않은 예외가 목표 내부 코드로
  변환된다.
- 오류 상태 코드와 테스트 기대값이 변경된 코드와 일치한다.
- 전체 AI 테스트와 Ruff 검증을 통과한다.

**명시적 제외:** Backend의 `HttpAiServiceClient` 오류 변환, multipart bundle,
`X-Internal-Token` 구현. 내부 IP/private network 제한을 전제로 토큰은 후속 연동 task로
보류한다.

**리뷰 및 리팩토링 결과:**

- AI Service의 오류 응답이 `AI_INVALID_REQUEST`, `AI_FILE_PROCESSING_FAILED`,
  `AI_GENERATION_FAILED`, `AI_INVALID_RESPONSE`, `AI_TIMEOUT`, `AI_UNAVAILABLE`으로
  정렬되었음을 확인했다.
- Mock 비활성 상태는 503 `AI_UNAVAILABLE`로 검증한다.
- 내부 HTTP 상태 코드의 최종 표준화와 Backend 사용자 API 변환은 공동 계약 task에서
  확정해야 하므로 변경하지 않았다.
- 중복 또는 불필요한 추상화가 없어 추가 리팩토링은 수행하지 않았다.

**검증:** `uv run pytest`, `uv run ruff check .`, `uv run ruff format --check .`
통과. pytest 18개 통과(Starlette TestClient deprecation warning 1건).

**다음 Task:** AI-03 — Backend-AI multipart bundle 및 내부 인증 계약 제안서 작성.

### AI-03 — Multipart bundle Mock 입력 처리

**상태:** 완료

**목표:** Backend가 전송하는 `request`, `manifest`, 반복 `files` multipart bundle을
AI Service가 수신·구조 검증한 뒤 Mock `ReportDocument`를 반환하게 한다.

**변경 범위:** `ai/`와 이 로컬 규칙 파일만 변경한다. 확정된
`contracts/report-generation.md`의 multipart 형식을 따른다.

**입력 검증:**

- `request`는 현재 `fileIds`, `metadata`, `instructions` JSON 계약을 따른다.
- `manifest.version`은 1이고, 각 파일은 `fileId`, `category`, `path`, `mimeType`,
  `size`를 가진다.
- `files` 개수·순서·filename은 manifest와 일치해야 하며 최대 1,000개다.
- manifest의 경로는 category와 원본 `fileId` 아래의 상대 POSIX 경로여야 한다.
- manifest 기준 총 파일 크기는 100 MiB를 넘지 않는다.

**내부 인증 결정:** Backend는 `X-Internal-Token`을 전송하지만, 현재 private network
제한을 전제로 AI Service의 토큰 강제 검증은 별도 보안 task로 보류한다. 이 결정은
배포 전에 재검토한다.

**완료 조건:**

- Backend와 동일한 multipart part 이름으로 Mock 생성 요청이 성공한다.
- manifest/file 불일치는 `AI_INVALID_REQUEST`로 거부한다.
- 정상·누락·순서 불일치·경로 위반·제한 초과 테스트와 Ruff 검증을 통과한다.

**명시적 제외:** Gemini 호출, 파일 내용의 문서·코드·이미지 분석, 업로드 바이트의
영구 저장, AI Service의 내부 토큰 강제 검증.

**리뷰 및 리팩토링 결과:**

- Backend `GenerationBundleFactory`와 `HttpAiServiceClient`가 보내는 part 이름,
  manifest 순서, 파일명, MIME, 크기 계약과 일치함을 확인했다.
- 요청 JSON과 manifest JSON은 모델 검증 실패 시 `AI_INVALID_REQUEST`로 변환한다.
- 계약 범위 안의 중복 또는 불필요한 추상화는 발견되지 않았다.
- AI README에 multipart API 사용 방식과 구현 완료 범위를 반영했다.

**검증:** `uv run pytest`, `uv run ruff check .`, `uv run ruff format --check .`,
`git diff --check` 통과. pytest 23개 통과(Starlette TestClient deprecation warning 1건).

**재리뷰 보완:** manifest의 모든 `fileId`가 request `fileIds`의 부분집합인지 검증하고,
두 파일의 multipart 순서를 실제로 바꾼 요청을 거부하는 회귀 테스트를 추가했다.
재검증에서 pytest 25개가 통과했다.

**다음 Task:** AI-04 — 문서·코드·이미지 입력 정규화와 Gemini Client 설계.

### AI-04 — 입력 정규화 및 Gemini Client 기반

**상태:** 완료 (PR #91 Open, 병합 대기)

**목표:** 검증된 multipart bundle을 실제 Gemini 분석에 사용할 수 있는 안전하고 제한된
분석 컨텍스트로 정규화하고, Gemini 호출을 서비스 경계 뒤에 둘 기반을 만든다.

**최신 계약 반영:** `GenerationRequest.metadata`는 임의 객체가 아니라
`ReportDocument.metadata`와 같은 `title` 필수, `author`·`course`·`date` 선택 구조를
따른다. 허용되지 않은 metadata 키는 거부한다.

**개발 범위:**

- MD/TXT를 UTF-8 텍스트 자료로 읽고 파일 경로·원본 `fileId`와 함께 정규화한다.
- source 파일을 언어·경로·내용·원본 `fileId` 단위로 정규화한다.
- PNG/JPG 파일은 바이트를 영구 저장하지 않고 Gemini Vision 입력에 사용할 참조로 정규화한다.
- 입력 파일별·총 텍스트 크기 상한을 적용하고 잘린 자료를 명시한다.
- `GEMINI_API_KEY`, model, timeout, retry를 사용하는 Gemini Client 인터페이스와
  오류 변환 경계를 만든다. 이 task에서는 Mock 기본 동작을 유지한다.

**설계 원칙:**

- 코드·문서 원문, 이미지 바이트, 프롬프트, API Key는 로그·오류 응답에 포함하지 않는다.
- 근거 파일 경로와 이미지 `fileId`를 분석 결과에 유지해 최종 보고서의 사실성을 보장한다.
- Gemini는 최종 보고서를 한 번에 작성하지 않고, 후속 task에서 요구사항·코드·이미지 분석,
  보고서 계획, `ReportDocument` 생성 순으로 호출한다.

**완료 조건:**

- strict metadata 검증과 bundle 정규화 모델·단위 테스트가 있다.
- 텍스트 경계·UTF-8 오류·이미지 MIME·잘림 상태를 테스트한다.
- Gemini Client가 키 누락, timeout, API 오류를 AI 내부 오류 코드로 변환한다.
- Mock 모드에서는 외부 Gemini 호출이 발생하지 않는다.

**명시적 제외:** 실제 Gemini 프롬프트 다단계 실행, 최종 `ReportDocument` 생성,
AI Service 내부 토큰 강제 검증, Backend·contracts 변경.

**리뷰 및 리팩토링 결과:**

- 선택형 날짜는 ISO-8601 날짜로 파싱되도록 명시 타입을 적용했다.
- Gemini SDK 오류는 timeout 및 5xx 응답만 제한적으로 재시도하며, 4xx 오류는 즉시
  내부 오류 코드로 변환한다. 재시도에는 최대 2초의 지수 백오프를 적용한다.
- 정규화·metadata·Gemini Client 회귀 테스트를 추가했고, `pytest` 44개와 Ruff lint·format,
  `git diff --check`를 통과했다.

**다음 Task:** AI-05 — Gemini 다단계 분석 파이프라인 및 ReportDocument 생성 설계.

### AI-05 — Gemini 다단계 분석 및 ReportDocument 생성

**상태:** 계획 및 설계 진행 중

**브랜치 전략:** `feat/ai/gemini-analysis-pipeline`는 PR #91의
`feat/ai/gemini-input-foundation` 위에 쌓는 임시 스택 브랜치다. PR #91 병합 후 `dev` 위로
재정렬해 독립 PR로 만든다.

**목표:** 정규화된 분석 컨텍스트에서 요구사항·소스·이미지 근거를 단계별로 추출하고,
그 결과만 이용해 JSON Schema에 맞는 `ReportDocument`를 생성한다.

**구현 순서:**

1. 각 분석 단계의 입력·출력 Pydantic 모델과 JSON 전용 프롬프트를 정의한다.
2. 과제 요구사항, 소스 코드·설정, 스크린샷을 독립 분석한다.
3. 분석 결과를 기반으로 보고서 계획과 `ReportDocument`를 생성·검증한다.
4. Gemini 오류·빈 결과·잘못된 JSON·근거 불일치에 대한 회귀 테스트를 추가한다.

**병렬화 기준:** 데이터 모델·프롬프트·단위 테스트는 독립적으로 진행할 수 있지만,
실제 Gemini 호출과 최종 문서 조합은 이전 단계 출력에 의존하므로 순차 연결한다.

**명시적 제외:** 공통 `ReportDocument` 계약 변경, Backend API 변경, 사용자별 Gemini Key,
PDF 생성, AI Service 내부 토큰 강제 검증.

## Git 규칙

- 사용자로부터 명시적인 명령이 있기 전에는 `git commit`을 실행하지 않는다.
- 사용자로부터 명시적인 명령이 있기 전에는 `git push`를 실행하지 않는다.
- 사용자로부터 명시적인 명령이 있기 전에는 Pull Request를 생성하거나 수정하지 않는다.
- `git status`, diff, log, fetch 등 읽기·확인 목적의 Git 명령은 허용한다.
- 원격 변경 반영을 위한 pull은 사용자 요청이 있을 때만 수행한다.

## AI 담당 작업 원칙

- AI Service는 Backend 전용 내부 서비스로 유지한다.
- AI 입력·응답 및 `ReportDocument` 계약 변경은 단독으로 결정하지 않는다.
- Gemini API Key, 원본 파일 내용, 프롬프트 원문을 로그나 오류 응답에 노출하지 않는다.
- AI 결과는 반환 전에 JSON Schema로 검증하고, Backend의 최종 검증을 전제로 한다.
