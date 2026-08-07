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

`JWT_SECRET`을 먼저 설정해야 실행할 수 있다. 나머지 환경변수는 기본값을 사용하거나 필요한 경우 변경한다.

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
| `UPLOAD_PATH` | `./uploads` |
| `EXPORT_PATH` | `./generated-reports` |
| `EXPORT_TTL` | `24h` |
| `JWT_SECRET` | 필수 (32바이트 이상의 임의 문자열) |

비밀정보는 `.env` 또는 IntelliJ Run Configuration에 저장하고 커밋하지 않는다. Spring Boot는 `.env` 파일을 자동으로 읽지 않으므로 IntelliJ의 환경변수 항목에 입력하거나 터미널에서 내보내야 한다.

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

| 코드 | HTTP 상태 | 설명 |
| --- | --- | --- |
| `AI_SERVICE_ERROR` | 502 | AI Service가 오류 응답 반환 |
| `AI_SERVICE_UNAVAILABLE` | 502 | AI Service 연결 실패 |
| `AI_SERVICE_INVALID_RESPONSE` | 502 | 비어 있거나 올바르지 않은 응답 |
| `AI_SERVICE_TIMEOUT` | 504 | AI Service 응답 시간 초과 |

## 비동기 보고서 생성

`POST /api/projects/{projectId}/generations`는 생성 작업을 `PENDING`으로 저장한 뒤 즉시
Job ID를 반환한다. 요청 본문과 `document`는 모두 선택이며, 프로젝트당 활성 작업은 하나만 허용한다.

- 상태: `PENDING` → `PROCESSING` → `COMPLETED` 또는 `FAILED`
- 진행률: `0` → `10` → `100`(성공 시)
- 단계: `QUEUED` → `CALLING_AI` → `COMPLETED` 또는 `FAILED`
- 재시작 시 `PENDING`은 재실행하고 `PROCESSING`은 `GENERATION_INTERRUPTED`로 실패 처리
- AI 응답 ReportDocument는 JSON Schema 검증 후 Report에 저장하고 Job의 `reportId`로 연결

`GET /api/generations/{jobId}`에서 상태·진행률·단계와 실패 코드·메시지를 조회한다.
활성 작업이 이미 있으면 409 `GENERATION_ALREADY_RUNNING`을 반환한다.

## 보고서

- `GET /api/reports/{reportId}`: 소유한 프로젝트의 ReportDocument 조회
- `PUT /api/reports/{reportId}`: JSON Schema가 유효한 ReportDocument로 수정
- JSONB 문서와 낙관적 잠금 버전, 생성·수정 시각을 함께 관리
- 다른 사용자의 보고서는 존재 여부를 노출하지 않고 404 `REPORT_NOT_FOUND` 반환
- 스키마 불일치는 400 `REPORT_DOCUMENT_INVALID` 반환

## PDF 내보내기

- `POST /api/reports/{reportId}/exports`: PDF 생성 작업 접수
- `GET /api/report-exports/{exportId}`: 생성 상태·실패 원인·만료 시각 조회
- `GET /api/report-exports/{exportId}/download`: 소유자만 완성된 PDF 다운로드
- PDF는 `EXPORT_PATH/{exportId}.pdf`에 저장하고 `EXPORT_TTL` 후 만료 처리
- 서버 재시작 시 대기 작업은 재실행하고 처리 중 작업은 `EXPORT_INTERRUPTED`로 실패 처리
- 다른 사용자의 작업은 404, 준비 전·실패 작업은 409, 만료는 410으로 응답

## 파일 업로드

- 파일당 최대 크기: 20 MiB
- 문서·소스: PDF, DOCX, TXT, MD, ZIP
- 이미지: JPG/JPEG, PNG
- GIF와 WEBP는 MVP의 보고서·AI 처리 호환 범위에 포함되지 않아 업로드할 수 없다.
- 확장자, 요청 MIME, 실제 파일 형식이 모두 일치해야 한다.
- 실제 파일은 `UPLOAD_PATH/{projectId}/{fileId}`에 UUID 이름으로 저장한다.

업로드 오류는 공통 오류 응답의 `code`로 구분한다.

| 코드 | HTTP 상태 | 설명 |
| --- | --- | --- |
| `FILE_EMPTY` | 400 | 빈 파일 |
| `FILE_NAME_INVALID` | 400 | 파일 이름 누락 또는 255자 초과 |
| `FILE_TOO_LARGE` | 413 | 20 MiB 초과 |
| `FILE_TYPE_NOT_ALLOWED` | 415 | 지원하지 않는 형식 또는 확장자·MIME·실제 형식 불일치 |
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
