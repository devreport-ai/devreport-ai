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

기본값으로 바로 실행할 수 있으며 필요한 경우 다음 값을 설정한다.

| 변수 | 기본값 |
| --- | --- |
| `BACKEND_PORT` | `8080` |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/devreport` |
| `DATABASE_USERNAME` | `devreport` |
| `DATABASE_PASSWORD` | `devreport` |
| `POSTGRES_PORT` | `5432` |
| `AI_SERVICE_URL` | `http://localhost:8000` |
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
export JWT_SECRET='32바이트-이상의-안전한-임의-문자열'
./gradlew bootRun
```

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
