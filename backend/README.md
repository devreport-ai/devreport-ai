# DevReport AI Backend

DevReport AI의 인증, 프로젝트, 파일, 생성 작업과 보고서를 관리하는 Spring Boot 서비스이다.

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
| `UPLOAD_PATH` | `./uploads` |
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
| `FILE_STORAGE_ERROR` | 500 | 메타데이터와 실제 저장 파일 불일치 또는 저장소 처리 실패 |

파일 목록은 `page`(기본 0)와 `size`(기본 20, 최대 100)로 나눠 조회한다.

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
