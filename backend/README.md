# DevReport AI Backend

DevReport AI의 인증, 프로젝트, 파일, 생성 작업과 보고서를 관리하는 Spring Boot 서비스이다.

## 패키지 구조

| 패키지 | 역할 |
| --- | --- |
| `auth` | 사용자·Refresh Token·JWT 인증 기능 |
| `project` | 프로젝트 CRUD 기능 |
| `upload` | 파일 저장·검증·ZIP 해제·프로젝트 휴지통 정리 기능 |
| `generation` | 비동기 AI 보고서 생성 작업 기능 |
| `report` | ReportDocument 검증·보고서 저장·수정 기능 |
| `export` | PDF 생성 작업·다운로드·만료 처리 기능 |
| `usage` | 비식별 서비스 사용 이벤트 기록·보존 기능 |
| `integration.ai` | FastAPI AI Service 연동 |
| `common.error` | 공통 API 오류 응답 |
| `config` | 보안·비동기 실행 설정 |

각 기능 패키지는 필요한 범위에서 `api`, `application`, `domain`, `infrastructure`로 나눈다.
각각 HTTP 입출력, 유스케이스 조정, 핵심 상태, DB·파일·외부 연동 구현을 담당한다.
단순 계층화를 위한 모델이나 인터페이스는 중복 생성하지 않는다.

## 요구사항

- Java 21
- Docker 및 Docker Compose

시스템 Gradle 설치는 필요하지 않으며 저장소의 Gradle Wrapper를 사용한다.

## IntelliJ 설정

1. IntelliJ에서 모노레포 루트 `devreport-ai/`를 연다.
2. Project SDK를 Java 21로 설정한다.
3. `backend/build.gradle`을 Gradle 프로젝트로 연결한다.
4. `BackendApplication`을 실행한다.

## 환경변수

로컬 실행은 `JWT_SECRET`(32바이트 이상)을 설정하면 아래 기본값으로 가능하며, 실제 AI 생성 호출에는 `AI_INTERNAL_TOKEN`이 필요하다.
운영은 `SPRING_PROFILES_ACTIVE=prod`를 사용해야 하고, 운영 필수 Secret이 없으면 기동에 실패한다.

| 변수 | 기본값 |
| --- | --- |
| `BACKEND_PORT` | `8080` |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/devreport` |
| `DATABASE_USERNAME` | `devreport` |
| `DATABASE_PASSWORD` | `devreport` |
| `POSTGRES_PORT` | `5432` |
| `AI_SERVICE_URL` | `http://localhost:8000` |
| `AI_SERVICE_CONNECT_TIMEOUT` | `3s` |
| `AI_SERVICE_RESPONSE_TIMEOUT` | `300s` |
| `AI_SERVICE_MOCK` | `false` |
| `AI_INTERNAL_TOKEN` | 없음 (실제 AI 생성·운영에서 필수) |
| `UPLOAD_PATH` | `./uploads` |
| `EXPORT_PATH` | `./generated-reports` |
| `EXPORT_TTL` | `24h` |
| `EXPORT_PRINT_URL` | 운영 필수 (`{exportId}`를 포함한 Frontend 출력 route URL) |
| `EXPORT_RENDER_TOKEN_TTL` | `1m` |
| `EXPORT_RENDER_TIMEOUT` | `30s` |
| `JWT_SECRET` | 필수 (32바이트 이상의 임의 문자열) |
| `AUTH_REFRESH_TOKEN_COOKIE_NAME` | `refresh_token` |
| `AUTH_REFRESH_TOKEN_COOKIE_PATH` | `/api/auth` |
| `AUTH_REFRESH_TOKEN_COOKIE_SECURE` | `false` (prod 프로필에서는 `true`) |
| `AUTH_REFRESH_TOKEN_COOKIE_SAME_SITE` | `Lax` (`Strict`, `Lax`, `None`) |
| `PUBLIC_APP_URL` | `http://localhost:3000` (재설정 링크의 공개 Frontend URL) |
| `PASSWORD_RESET_FROM` | 없음 (Resend에서 인증한 도메인의 발신 주소) |
| `PASSWORD_RESET_TOKEN_TTL` | `30m` |
| `RESEND_API_KEY` | 없음 (운영에서 필수) |
| `RESEND_CONNECT_TIMEOUT` | `3s` |
| `RESEND_RESPONSE_TIMEOUT` | `10s` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` (운영 동일 Origin이면 빈 값, cross-origin일 때만 allowlist) |
| `USAGE_LIMITS_DAY_ZONE` | `Asia/Seoul` |
| `USAGE_LIMITS_UPLOAD_MAX_TOTAL_BYTES` | `524288000` (500 MiB) |
| `USAGE_LIMITS_UPLOAD_MAX_FILES` | `100` |
| `USAGE_LIMITS_GENERATION_DAILY_LIMIT` | `10` |
| `USAGE_LIMITS_GENERATION_CONCURRENT_LIMIT` | `2` |
| `USAGE_LIMITS_EXPORT_DAILY_LIMIT` | `20` |
| `USAGE_LIMITS_EXPORT_CONCURRENT_LIMIT` | `1` |
| `USAGE_LIMITS_RATE_WINDOW` | `1m` |
| `USAGE_LIMITS_RATE_SIGNUP` | `10` / window / IP |
| `USAGE_LIMITS_RATE_LOGIN` | `20` / window / IP |
| `USAGE_LIMITS_RATE_REFRESH` | `30` / window / IP |
| `USAGE_LIMITS_RATE_PASSWORD_RESET` | `5` / window / IP 및 이메일 해시 |
| `USAGE_LIMITS_RATE_PASSWORD_CHANGE` | `5` / window / user |
| `USAGE_LIMITS_RATE_UPLOAD` | `30` / window / user |
| `USAGE_LIMITS_RATE_GENERATION` | `10` / window / user |

비밀정보는 `.env` 또는 IntelliJ Run Configuration에 저장하고 커밋하지 않는다. Spring Boot는 `.env` 파일을 자동으로 읽지 않으므로 IntelliJ의 환경변수 항목에 입력하거나 터미널에서 내보내야 한다.

### 운영 설정

Frontend는 reverse proxy를 통해 Backend와 동일한 Origin으로 제공한다. 별도 Origin이
불가피할 때만 `CORS_ALLOWED_ORIGINS`에 정확한 Origin을 쉼표로 지정한다. 운영 Backend는
Swagger/OpenAPI를 비활성화하고 Actuator는 `health`만 노출한다.

```bash
export SPRING_PROFILES_ACTIVE=prod
export DATABASE_PASSWORD='운영 DB 비밀번호'
export JWT_SECRET="$(openssl rand -base64 48)"
export AI_INTERNAL_TOKEN="$(openssl rand -hex 32)"
export EXPORT_PRINT_URL='https://app.example.com/print/report-exports/{exportId}'
export PUBLIC_APP_URL='https://app.example.com'
export PASSWORD_RESET_FROM='DevReport AI <no-reply@mail.example.com>'
export RESEND_API_KEY='re_운영_API_Key'
cd backend && ./gradlew bootRun
```

`GEMINI_API_KEY`는 Backend가 아니라 AI Service 프로세스에 주입한다. Backend와 AI Service는
같은 서버 또는 private network에서만 연결하고 AI Service 포트를 외부에 publish하지 않는다.

## 인증

- `POST /api/auth/login`은 Access Token만 JSON 본문으로 반환하고 Refresh Token은 `HttpOnly`·`SameSite=Lax`·`Path=/api/auth` 쿠키로 설정한다.
- `POST /api/auth/refresh`는 요청 본문 없이 쿠키를 읽고, 기존 Refresh Token을 폐기한 뒤 회전된 쿠키와 Access Token을 반환한다.
- `POST /api/auth/logout`은 쿠키에 대응하는 서버 토큰을 폐기하고, 토큰이 없거나 이미 폐기된 경우에도 `Max-Age=0` 쿠키로 브라우저 값을 삭제한다.
- `POST /api/auth/password`는 현재 비밀번호를 확인한 뒤 새 비밀번호를 저장하고 사용자의 모든 Refresh Token과 브라우저 쿠키를 폐기한다. 성공하면 Frontend는 다시 로그인한다.
- Refresh Token 쿠키는 JavaScript와 `localStorage`에 노출하지 않는다. 보호 API는 기존 `Authorization: Bearer` 방식을 유지한다.
- Frontend가 Backend와 다른 Origin에서 실행되면 `CORS_ALLOWED_ORIGINS`에 정확한 Origin을 지정하고 요청에 `credentials: 'include'`를 사용한다. `*`와 credentials 조합은 허용하지 않는다.
- 운영에서는 `SPRING_PROFILES_ACTIVE=prod`를 사용한다. `SameSite=None`을 사용할 때는 반드시 Secure 쿠키를 함께 사용한다.
- 쿠키를 사용하는 로그인·재발급·로그아웃 요청은 허용된 Origin 또는 동일 출처만 통과한다. Origin과 Referer가 모두 없는 요청은 최신 브라우저가 교차 출처 POST에 Origin을 보낸다는 전제 아래 허용한다. Origin이 필요한 브라우저 교차 출처 요청을 위해 reverse proxy의 forwarded header 설정도 신뢰된 프록시로 제한한다.

## 실행

저장소 루트에서 PostgreSQL을 실행한다.

```bash
docker compose -f infra/compose.yaml up -d
```

호스트의 `5432` 포트를 다른 PostgreSQL이 사용 중이면 Compose와 Backend가 같은 포트를 사용하도록 변경한다.

```bash
POSTGRES_PORT=5433 docker compose -f infra/compose.yaml up -d
export DATABASE_URL=jdbc:postgresql://localhost:5433/devreport
```

Backend를 실행한다.

```bash
cd backend
export JWT_SECRET="$(openssl rand -base64 48)"
./gradlew bootRun
```

## AI Service Client

Client는 `AI_SERVICE_URL`의 `GET /health`를 호출한다. 연결 실패와 5xx 응답은 502,
응답 시간 초과는 504로 변환한다. 로컬에서 FastAPI 없이 확인하려면
`AI_SERVICE_MOCK=true`를 설정하며, Mock Client는 공통 계약의 샘플 ReportDocument를 반환한다.

보고서 생성은 선택 파일을 임시 bundle로 정제한 뒤 `X-Internal-Token` 헤더와
`multipart/form-data`로 AI Service에 전달한다. bundle은 성공·실패·취소 모두 호출 종료 즉시 삭제한다.
AI Service의 공통 오류 응답은 `contracts/report-generation.md`의 규칙에 따라 Backend 생성
오류 코드로 변환한다. 원격 `message`와 `details`는 사용자 응답과 로그에 전달하지 않는다.

| 코드 | HTTP 상태 | 설명 |
| --- | --- | --- |
| `GENERATION_REQUEST_INVALID` | 400 | AI Service가 생성 요청을 거부함 |
| `GENERATION_FAILED` | 502 | 파일 처리·생성·응답 검증 실패 또는 알 수 없는 오류 |
| `AI_SERVICE_UNAVAILABLE` | 502 | AI Service 연결 실패 또는 사용 불가 |
| `GENERATION_TIMEOUT` | 504 | AI Service 응답 시간 초과 |

## 비동기 보고서 생성

`POST /api/projects/{projectId}/generations`는 생성 작업을 `PENDING`으로 저장한 뒤 즉시
Job ID를 반환한다. 요청 본문과 `document`는 모두 선택이며, 프로젝트당 활성 작업은 하나만 허용한다.

- 상태: `PENDING` → `PROCESSING` → `COMPLETED`, `FAILED` 또는 `CANCELED`
- 진행률: `0` → `10` → `100`(성공 시)
- 단계: `QUEUED` → `CALLING_AI` → `COMPLETED` 또는 `FAILED`
- 재시작 시 `PENDING`은 재실행하고 `PROCESSING`은 `GENERATION_INTERRUPTED`로 실패 처리
- AI 응답 ReportDocument는 JSON Schema 검증 후 Report에 저장하고 Job의 `reportId`로 연결

`GET /api/generations/{jobId}`에서 상태·진행률·단계와 실패 코드·메시지를 조회한다.
`DELETE /api/generations/{jobId}`는 대기·실행 중인 작업을 취소하고 임시 bundle을 정리한다.
활성 작업이 이미 있으면 409 `GENERATION_ALREADY_RUNNING`을 반환한다.

## 보고서

- `GET /api/projects/{projectId}/reports`: 수정 시각과 ID 내림차순의 프로젝트 보고서 목록 조회
- `GET /api/reports/{reportId}`: 소유한 프로젝트의 Report envelope 조회
- `PUT /api/reports/{reportId}`: `expectedVersion`을 포함한 Report envelope 수정
- JSONB 문서·템플릿·표현 설정과 낙관적 잠금 버전, 생성·수정 시각을 함께 관리
- 오래된 `expectedVersion`은 저장하지 않고 409 `REPORT_VERSION_CONFLICT` 반환
- 다른 사용자의 보고서는 존재 여부를 노출하지 않고 404 `REPORT_NOT_FOUND` 반환
- 스키마 불일치는 400 `REPORT_DOCUMENT_INVALID` 반환

## PDF 내보내기

- `POST /api/reports/{reportId}/exports`: PDF 생성 작업 접수
- `GET /api/report-exports/{exportId}`: 생성 상태·실패 원인·만료 시각 조회
- `GET /api/report-exports/{exportId}/download`: 소유자만 완성된 PDF 다운로드
- `GET /api/report-exports/{exportId}/render-data`: 출력 토큰으로 요청 시점 보고서 snapshot 조회
- `GET /api/report-exports/{exportId}/files/{fileId}`: snapshot에 포함된 이미지의 출력 토큰 조회
- PDF는 `EXPORT_PATH/{exportId}.pdf`에 저장하고 `EXPORT_TTL` 후 만료 처리
- PDF 요청 시 보고서 문서·버전·템플릿·표현 설정을 snapshot으로 고정한다.
- `EXPORT_PRINT_URL`의 `{exportId}` route를 고정 Chromium으로 연다. 설정이 없거나
  `{exportId}`를 포함하지 않은 URL이면 `503 EXPORT_PRINT_URL_NOT_CONFIGURED`로
  PDF 요청을 접수하지 않는다.
- Chromium은 URL fragment에 `#token=<renderToken>`을 붙인다. Frontend 출력 route는
  fragment의 `token` 값을 읽어 `render-data`와 `files` API 요청마다
  `X-Render-Token` header로 전달해야 한다.
- 출력 route는 모든 폰트·이미지 로딩 후 `<html data-print-state="ready">`를 설정해야 한다.
- PDF는 A4·print background·CSS `@page` 크기 우선으로 생성한다.
- 서버 재시작 시 대기 작업은 재실행하고 처리 중 작업은 `EXPORT_INTERRUPTED`로 실패 처리
- 템플릿 미선택은 409 `REPORT_TEMPLATE_NOT_SELECTED`, 다른 사용자의 작업은 404,
  준비 전·실패 작업은 409, 만료는 410으로 응답

## 사용량 제한과 비용 보호

모든 제한은 Frontend가 아닌 Backend에서 검사한다. `app_users` 행을
`PESSIMISTIC_WRITE`로 잠근 트랜잭션에서 현재 파일·생성 Job·PDF Export를 집계하고,
그 트랜잭션 안에서 새 리소스를 저장한다. 따라서 같은 사용자의 여러 프로젝트 요청도
quota와 동시성 검사를 중복 통과할 수 없다. 파일 quota는 휴지통에 남아 있는 파일도
실제 저장 공간을 차지하는 동안 포함하며, 파일 삭제·휴지통 완전 삭제 뒤 DB 집계에서
자동으로 제외된다.

기본값은 공개 베타를 위한 보수적인 값이며 환경변수로 조정할 수 있다.

| 대상 | 기본 제한 | 초과 오류 |
| --- | --- | --- |
| 사용자 업로드 | 500 MiB, 100개 | 413 `UPLOAD_STORAGE_QUOTA_EXCEEDED` 또는 `UPLOAD_FILE_QUOTA_EXCEEDED` |
| AI 생성 | 하루 10회, 동시 2개 | 429 `GENERATION_DAILY_LIMIT_EXCEEDED` 또는 `GENERATION_CONCURRENCY_LIMIT_EXCEEDED` |
| PDF export | 하루 20회, 동시 1개 | 429 `PDF_DAILY_LIMIT_EXCEEDED` 또는 `PDF_CONCURRENCY_LIMIT_EXCEEDED` |
| 회원가입·로그인·refresh | 1분당 IP별 10·20·30회 | 429 `RATE_LIMIT_EXCEEDED` |
| 비밀번호 변경 | 1분당 사용자별 5회 | 429 `RATE_LIMIT_EXCEEDED` |
| 업로드·AI 생성 요청 | 1분당 사용자별 30·10회 | 429 `RATE_LIMIT_EXCEEDED` |

rate limit bucket은 `rate_limit_buckets`에 저장되며 Backend 재시작 뒤에도 현재 윈도우가
유지된다. 오래된 bucket은 `USAGE_LIMITS_RATE_RETENTION` 기간과
`USAGE_LIMITS_RATE_CLEANUP_CRON` 주기에 따라 정리된다. 제한을 거부하는 시점은 업로드 임시 파일, `GenerationJob`, PDF export가
생성되기 전이므로 초과 요청이 원본 파일·작업·임시 bundle을 남기지 않는다.

Gemini 사용량은 `usage_events`의 `GENERATION_REQUESTED`, `GENERATION_COMPLETED`,
`GENERATION_FAILED` 수로 추적한다. 일별 지표는 다음처럼 계산한다.

```sql
WITH day_boundary AS (
	SELECT date_trunc('day', CURRENT_TIMESTAMP AT TIME ZONE :dayZone) AS day_start
)
SELECT
  COUNT(*) FILTER (WHERE event_type = 'GENERATION_REQUESTED') AS requests,
  COUNT(*) FILTER (WHERE event_type = 'GENERATION_COMPLETED') AS completed,
  COUNT(*) FILTER (WHERE event_type = 'GENERATION_FAILED') AS failed
FROM usage_events, day_boundary
WHERE occurred_at >= (day_start AT TIME ZONE :dayZone)
  AND occurred_at < ((day_start + INTERVAL '1 day') AT TIME ZONE :dayZone);
```

`:dayZone`에는 `USAGE_LIMITS_DAY_ZONE`(기본값 `Asia/Seoul`)을 바인딩한다.

실패율은 `failed / (completed + failed)`로 계산하고, 예상 비용은
`requests * 0.05 USD`로 계산한다.

초기 운영 추정치는 생성 1회당 `0.05 USD`이며 기본 일일 제한의 예상 상한은
`0.50 USD`다. `0.40 USD`(상한의 80%)에서 비용 알림을 내고, 실패율이 20%를 넘거나
5회 연속 실패하면 장애 알림을 낸다. 실제 Gemini 청구액·토큰 사용량을 확인하면 이
추정 단가와 일일 quota를 함께 갱신한다. 현재 계약에는 AI 서비스의 토큰 사용량이
없으므로 비용 계산은 운영용 추정치이며, 결제·요금제는 범위에 포함하지 않는다.

Chromium binary는 Playwright 버전에 맞춰 설치한다.

```bash
cd backend
./gradlew installChromium
```

## 파일 업로드

- 파일당 최대 크기: 20 MiB
- 문서: PDF, DOCX, TXT, MD
- 소스: Java, Kotlin, Python, JavaScript/TypeScript 등 ZIP 분석 allowlist와 같은 텍스트 코드
- 묶음: ZIP
- 이미지: JPG/JPEG, PNG
- GIF와 WEBP는 MVP의 보고서·AI 처리 호환 범위에 포함되지 않아 업로드할 수 없다.
- 확장자, 요청 MIME, 실제 파일 형식이 모두 일치해야 한다.
- PDF는 암호화·손상·페이지 제한(최대 200페이지)을 추가로 검증한다.
- 실제 파일은 `UPLOAD_PATH/{projectId}/{fileId}`에 UUID 이름으로 저장한다.
- 문서·소스·ZIP·이미지는 AI 생성 입력으로 선택할 수 있다.

업로드 오류는 공통 오류 응답의 `code`로 구분한다.

| 코드 | HTTP 상태 | 설명 |
| --- | --- | --- |
| `FILE_EMPTY` | 400 | 빈 파일 |
| `FILE_NAME_INVALID` | 400 | 파일 이름 누락 또는 255자 초과 |
| `FILE_TOO_LARGE` | 413 | 20 MiB 초과 |
| `FILE_TYPE_NOT_ALLOWED` | 415 | 지원하지 않는 형식 또는 확장자·MIME·실제 형식 불일치 |
| `PDF_INVALID` | 400 | 손상되었거나 페이지가 없는 PDF |
| `PDF_ENCRYPTED` | 422 | 암호화된 PDF |
| `PDF_PAGE_LIMIT_EXCEEDED` | 413 | 200페이지 초과 PDF |
| `FILE_NOT_FOUND` | 404 | 프로젝트에 해당 파일이 없음 |
| `FILE_STORAGE_ERROR` | 500 | 업로드·조회·삭제·완전 삭제 중 저장소 처리 또는 정합성 검증 실패 |

파일 목록은 `page`(기본 0)와 `size`(기본 20, 최대 100)로 나눠 조회한다.

`GET /api/projects/{projectId}/files/{fileId}`는 기존 Bearer Token으로 파일 내용을 조회한다.
PNG·JPG/JPEG·PDF는 브라우저 미리보기를 위해 `inline`, 나머지는 `attachment`로 응답하며
모든 응답에 `Cache-Control: no-store`를 적용한다. Frontend는 인증 요청으로 받은 Blob URL을
편집기와 출력 페이지에서 사용한다.

### ZIP 보안 검사

ZIP은 업로드 중 안전한 임시 경로에 해제한 뒤 분석 대상 파일만
`UPLOAD_PATH/{projectId}/{fileId}.extracted`에 저장한다.

- 압축 해제 총용량 최대 100 MiB, 내부 엔트리 최대 1,000개
- `..`, 절대 경로, Windows 드라이브 경로를 차단해 ZIP Slip 방지
- `.env*`, 인증서·키, 실행 파일 확장자와 실행 바이너리 헤더가 있으면 전체 업로드 거부
- `.git`, `node_modules`, `build`, `.gradle` 경로는 해제하지 않음
- 분석 확장자: `java`, `kt`, `py`, `js`, `jsx`, `ts`, `tsx`, `html`, `css`, `scss`,
  `sql`, `xml`, `json`, `yaml`, `yml`, `md`, `txt`, `gradle`, `properties`, `toml`, `go`,
  `rs`, `c`, `h`, `cpp`, `hpp`, `cs`, `php`, `rb`, `swift`, `dart`, `vue`, `svelte`
- 실패 시 임시 해제 파일을 삭제하며, 원본 ZIP 삭제 시 해제 디렉터리도 함께 삭제

| 코드 | HTTP 상태 | 설명 |
| --- | --- | --- |
| `ZIP_INVALID` | 400 | 손상되었거나 중복 경로가 있는 ZIP |
| `ZIP_PATH_INVALID` | 400 | ZIP Slip 위험 경로 |
| `ZIP_LIMIT_EXCEEDED` | 413 | 해제 용량 또는 엔트리 개수 초과 |
| `ZIP_BLOCKED_CONTENT` | 422 | 환경변수·인증서·키·실행 파일 포함 |

## 프로젝트 휴지통

프로젝트 삭제 API는 프로젝트와 업로드 파일을 즉시 지우지 않고 휴지통으로 이동한다.
일반 프로젝트·파일 API에서는 휴지통 프로젝트를 조회하거나 변경할 수 없다.

- `GET /api/projects/trash`: 휴지통 목록 조회
- `POST /api/projects/{projectId}/restore`: 프로젝트와 파일 복구
- 삭제 후 30일 경과: 매일 오전 3시에 DB 메타데이터와 실제 저장 파일을 완전 삭제

장기 분석에는 원본 파일을 영구 보관하지 않고 별도로 정의한 비식별 사용 이벤트를 사용한다.

## 비식별 사용 이벤트

사용 흐름과 장애를 분석하기 위해 다음 이벤트를 PostgreSQL에 최대 90일 보관한다.

| 이벤트 | 연결 ID | 허용 metadata |
| --- | --- | --- |
| `PROJECT_CREATED` | user, project | 없음 |
| `FILE_UPLOADED` | user, project, file | `contentType`, `sizeBytes` |
| `GENERATION_REQUESTED` | user, project, job | `fileCount` |
| `GENERATION_COMPLETED` | user, project, job, report | 없음 |
| `GENERATION_FAILED` | user, project, job | 허용 목록의 `failureCode` |
| `REPORT_EDITED` | user, project, report | `previousVersion` |
| `PDF_EXPORTED` | user, project, report, export | `sizeBytes` |

파일 이름·내용, AI `metadata`·`instructions`, 보고서 문서와 개인정보는 이벤트 metadata나
사용 이벤트 전용 로그에 저장하지 않는다. 프로젝트를 휴지통에서 30일 후 완전 삭제하거나
회원을 삭제하면 외래 키 `CASCADE`로 연결 이벤트도 삭제한다. 파일·보고서·작업·내보내기
단독 삭제 시에는 이벤트를 보존하되 해당 리소스 ID만 `NULL`로 만든다.

## 테스트

```bash
cd backend
./gradlew clean test
```

## 확인

- Health Check: <http://localhost:8080/actuator/health>
- Swagger UI: <http://localhost:8080/swagger-ui/index.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>

PostgreSQL을 종료하려면 저장소 루트에서 실행한다.

```bash
docker compose -f infra/compose.yaml down
```
