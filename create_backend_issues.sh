#!/usr/bin/env bash
set -euo pipefail

REPO="devreport-ai/devreport-ai"

command -v gh >/dev/null || { echo "gh CLI가 필요합니다: https://cli.github.com/"; exit 1; }
gh auth status >/dev/null

echo "Backend 이슈 9개를 생성합니다..."
gh issue create --repo "$REPO" --assignee ii2001 --title "[Backend] Spring Boot 프로젝트 초기 환경 구성" --body-file - <<'EOF_8774469311388206990'
## 목표
Backend 서비스의 실행 기반과 PostgreSQL 개발 환경을 구축한다.

## 작업 범위
- [ ] Java 21 기반 Spring Boot 프로젝트 생성
- [ ] Gradle Wrapper 포함
- [ ] Spring Web, Validation, Data JPA, Security, Actuator 구성
- [ ] PostgreSQL 및 Flyway 연동
- [ ] Docker Compose로 PostgreSQL 실행 환경 구성
- [ ] Swagger/OpenAPI UI 구성
- [ ] 공통 예외 응답 기본 구조 작성
- [ ] 환경변수 기반 설정 적용
- [ ] `backend/README.md` 실행 방법 작성

## 완료 조건
- [ ] `./gradlew clean test` 성공
- [ ] 애플리케이션 실행 성공
- [ ] PostgreSQL 연결 성공
- [ ] `/actuator/health` 응답이 `UP`
- [ ] Swagger UI 접근 가능

## 브랜치
`feat/backend/project-init`
EOF_8774469311388206990

gh issue create --repo "$REPO" --assignee ii2001 --title "[Backend] 회원가입 및 JWT 인증 구현" --body-file - <<'EOF_3721547090505339031'
## 목표
사용자별 프로젝트와 보고서를 안전하게 관리하기 위한 인증 기능을 구현한다.

## 작업 범위
- [ ] User 및 RefreshToken 엔티티
- [ ] 회원가입 API
- [ ] 로그인 API
- [ ] Access Token 발급
- [ ] Refresh Token 재발급
- [ ] 로그아웃
- [ ] 내 정보 조회
- [ ] BCrypt 비밀번호 해시
- [ ] 인증 예외 처리

## 완료 조건
- [ ] 회원가입·로그인 통합 테스트 통과
- [ ] 인증이 필요한 API에 미인증 접근 차단
- [ ] Refresh Token 폐기 및 만료 처리 확인

## 브랜치
`feat/backend/auth`
EOF_3721547090505339031

gh issue create --repo "$REPO" --assignee ii2001 --title "[Backend] 프로젝트 생성 및 조회 API 구현" --body-file - <<'EOF_9031450155855942263'
## 목표
로그인 사용자가 자신의 보고서 프로젝트를 생성하고 관리할 수 있게 한다.

## 작업 범위
- [ ] Project 엔티티 및 Flyway 마이그레이션
- [ ] 프로젝트 생성
- [ ] 프로젝트 목록 및 상세 조회
- [ ] 프로젝트 수정 및 삭제
- [ ] 소유자 권한 검증

## 완료 조건
- [ ] 다른 사용자의 프로젝트 접근 차단
- [ ] API 및 Repository 테스트 통과
- [ ] OpenAPI 계약 반영

## 브랜치
`feat/backend/project-api`
EOF_9031450155855942263

gh issue create --repo "$REPO" --assignee ii2001 --title "[Backend] 프로젝트 파일 업로드 API 구현" --body-file - <<'EOF_1844795274241159151'
## 목표
과제 문서, 소스코드 ZIP, 스크린샷을 프로젝트 단위로 업로드하고 관리한다.

## 작업 범위
- [ ] UploadedFile 엔티티
- [ ] PDF, DOCX, TXT, MD, ZIP, 이미지 업로드
- [ ] 확장자·MIME·파일 크기 검증
- [ ] 로컬 저장소 구현
- [ ] 파일 목록 조회 및 삭제
- [ ] 프로젝트 소유권 검증

## 완료 조건
- [ ] 허용·차단 파일 테스트 통과
- [ ] 파일 메타데이터와 실제 파일 정합성 확인
- [ ] 업로드 제한 오류 응답 표준화

## 브랜치
`feat/backend/file-upload`
EOF_1844795274241159151

gh issue create --repo "$REPO" --assignee ii2001 --title "[Backend] ZIP 압축 해제 및 보안 검증 구현" --body-file - <<'EOF_8577494159888085777'
## 목표
업로드된 프로젝트 ZIP을 안전하게 해제하고 AI 분석 대상 파일을 선별한다.

## 작업 범위
- [ ] ZIP Slip 방어
- [ ] 압축 해제 후 최대 용량 제한
- [ ] 내부 파일 개수 제한
- [ ] `.env`, 인증서, 실행 파일 차단
- [ ] `.git`, `node_modules`, `build`, `.gradle` 제외
- [ ] 분석 대상 확장자 선별

## 완료 조건
- [ ] 악성 경로 및 ZIP bomb 방어 테스트 통과
- [ ] 제외 규칙 테스트 통과
- [ ] 실패 시 임시 파일 정리

## 브랜치
`feat/backend/zip-validation`
EOF_8577494159888085777

gh issue create --repo "$REPO" --assignee ii2001 --title "[Backend] FastAPI AI Service Client 구현" --body-file - <<'EOF_3587203037548687269'
## 목표
Spring Backend가 내부 FastAPI AI Service를 안정적으로 호출할 수 있게 한다.

## 작업 범위
- [ ] AI Service Client 인터페이스
- [ ] HTTP 요청·응답 DTO
- [ ] 연결·응답 타임아웃
- [ ] AI 오류를 Backend 오류로 변환
- [ ] Mock AI Client
- [ ] 샘플 ReportDocument 연동

## 완료 조건
- [ ] Mock 및 실제 FastAPI Health Check 연동
- [ ] 타임아웃·5xx 오류 테스트 통과
- [ ] AI Service URL 환경변수 적용

## 브랜치
`feat/backend/ai-client`
EOF_3587203037548687269

gh issue create --repo "$REPO" --assignee ii2001 --title "[Backend] 비동기 보고서 생성 작업 관리 구현" --body-file - <<'EOF_6092282104490567983'
## 목표
보고서 생성 요청을 비동기 작업으로 접수하고 진행 상태를 관리한다.

## 작업 범위
- [ ] GenerationJob 엔티티
- [ ] 생성 요청 접수 API
- [ ] PENDING, PROCESSING, COMPLETED, FAILED 상태 관리
- [ ] AI Service 호출
- [ ] 진행률 및 현재 단계 저장
- [ ] 실패 코드와 메시지 저장
- [ ] 상태 조회 API

## 완료 조건
- [ ] 생성 요청이 즉시 Job ID 반환
- [ ] 성공·실패 상태 전이 테스트 통과
- [ ] 중복 실행 방지 정책 적용

## 브랜치
`feat/backend/generation-job`
EOF_6092282104490567983

gh issue create --repo "$REPO" --assignee ii2001 --title "[Backend] 보고서 조회 및 수정 API 구현" --body-file - <<'EOF_1035304916803281466'
## 목표
AI가 생성한 ReportDocument를 저장하고 사용자가 조회·수정할 수 있게 한다.

## 작업 범위
- [ ] Report 엔티티
- [ ] JSON/JSONB ReportDocument 저장
- [ ] 보고서 조회 및 수정 API
- [ ] JSON Schema 검증
- [ ] 버전 및 수정 시각 관리
- [ ] 사용자 권한 검증

## 완료 조건
- [ ] 유효하지 않은 ReportDocument 저장 차단
- [ ] 사용자별 접근 제어 테스트 통과
- [ ] contracts 스키마와 호환

## 브랜치
`feat/backend/report-api`
EOF_1035304916803281466

gh issue create --repo "$REPO" --assignee ii2001 --title "[Backend] 보고서 PDF 생성 및 다운로드 구현" --body-file - <<'EOF_415171447592630816'
## 목표
저장된 보고서를 PDF로 변환하고 안전하게 다운로드할 수 있게 한다.

## 작업 범위
- [ ] ReportExport 엔티티
- [ ] PDF 생성 요청 및 상태 관리
- [ ] ReportDocument 기반 PDF 렌더링
- [ ] 결과 파일 저장
- [ ] 다운로드 API
- [ ] 실패 및 만료 파일 처리

## 완료 조건
- [ ] 샘플 보고서 PDF 생성 성공
- [ ] 다른 사용자의 PDF 다운로드 차단
- [ ] 생성 실패 및 파일 없음 처리 테스트 통과

## 브랜치
`feat/backend/pdf-export`
EOF_415171447592630816

echo "이슈 생성 완료."