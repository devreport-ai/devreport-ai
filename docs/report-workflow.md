# MVP 보고서 생성·편집 흐름

이 문서는 DevReport AI MVP의 사용자 흐름과 서비스별 책임을 정의한다.

> 구현 상태: 이 문서는 목표 흐름이다. 생성 입력은 #34, 파일 참조 검증은 #37,
> Report 표현 상태·자동 저장 충돌 처리는 #32, AI 오류 변환은 #30,
> Frontend route 기반 Chromium PDF는 #40에서 구현한다.

## 서비스 흐름

```text
Frontend → Spring Backend → FastAPI AI Service → Gemini
Gemini → FastAPI AI Service → Spring Backend → Frontend
```

Frontend는 AI Service나 Gemini를 직접 호출하지 않는다. AI Service의 8000 포트는 같은
서버 또는 Docker private network 안에서만 사용하며 외부에 공개하지 않는다.

## 사용자 흐름

1. 사용자가 프로젝트를 생성하고 자료를 업로드한다.
2. 분석 대상 파일을 선택하고 `metadata`와 `instructions`를 입력한다.
3. Frontend가 Backend에 보고서 생성을 요청한다.
4. Backend가 비동기 `GenerationJob`을 실행하고 Frontend는 상태를 polling한다.
5. 생성 중에도 사용자는 Frontend에서 디자인 템플릿을 선택하거나 변경할 수 있다.
6. AI Service가 구조화된 `ReportDocument`를 반환한다.
7. Backend가 문서 구조와 파일 참조를 검증한 뒤 Report로 저장한다.
8. Frontend가 선택한 템플릿으로 문서를 렌더링한다.
9. 사용자가 section과 block을 직접 편집하면 Frontend가 변경 내용을 자동 저장한다.
10. Frontend가 `POST /api/reports/{reportId}/exports`로 PDF 생성을 요청하고 `exportId`를 받는다.
11. Frontend가 export 상태를 polling하고 실패·만료 상태를 처리한다.
12. 완료되면 download endpoint에서 PDF를 받는다. Backend는 Frontend의 출력 전용 route를
    Headless Chromium으로 렌더링한다.

## 콘텐츠와 표현 분리

AI 결과는 전체 HTML이나 하나의 Markdown 문서가 아니라 JSON 구조의 `ReportDocument`다.
paragraph 등 텍스트 콘텐츠에는 계약에서 허용한 Markdown 문법 일부를 사용할 수 있고,
디자인과 레이아웃은 Frontend가 담당한다.

`ReportDocument`는 안정적인 section/block ID를 가져야 하며 Frontend는 block 단위로 수정,
이동, 추가, 삭제할 수 있어야 한다. MVP block type은 다음과 같다.

```text
paragraph, bulletList, code, table, image, callout, pageBreak
```

Report는 콘텐츠와 표현 상태를 분리해 관리한다.

```text
Report
├── document: ReportDocument
├── templateId
├── templateVersion
└── presentationSettings
```

템플릿을 바꿔도 `ReportDocument` 콘텐츠는 유지되어야 한다. 디자인용 `templateId`와
`templateVersion`은 AI 콘텐츠 생성의 필수 입력이 아니다. 콘텐츠 구조를 바꿀 필요가
확인되면 `reportType`이나 `contentProfile`처럼 디자인 템플릿과 구분되는 계약을 별도로 정의한다.

## 서비스별 책임

### Frontend

- 프로젝트 생성과 업로드 UI
- 분석 대상 선택, `metadata`, `instructions` 입력과 생성 요청
- 생성 상태 polling과 생성 중 디자인 템플릿 선택
- `ReportDocument` 렌더링과 block 단위 편집
- 자동 저장, 템플릿 전환, A4 Preview
- PDF 출력용 print route와 HTML/CSS 제공

### Backend

- 인증·인가와 프로젝트 관리
- 파일 저장, 보안 검증, 접근 제어
- 생성 요청 검증과 `GenerationJob` 관리
- 안전한 파일 bundle 구성과 AI Service 호출 orchestration
- AI 응답의 JSON Schema와 파일 참조 검증
- Report와 표현 상태 저장·수정, 자동 저장 충돌 처리
- 비식별 사용 이벤트 기록
- 고정 Headless Chromium 환경의 최종 PDF 생성

### AI Service

- Backend가 검증·선별해 전달한 자료 분석
- 서버 환경변수 `GEMINI_API_KEY`로 Gemini 호출
- JSON Schema에 맞는 구조화된 `ReportDocument` 생성과 검증
- AI 처리 오류를 내부 공통 계약으로 반환

## MVP 입력 파일

AI 보고서 생성 입력은 `ZIP`, `MD`, `TXT`, `PNG`, `JPG/JPEG`만 지원한다. 업로드 API가
별도로 보관하거나 미리보기할 수 있는 형식과 AI 생성 입력 형식은 구분한다. `PDF`와
`DOCX`는 필요성이 확인된 뒤 추가한다.

ZIP은 소스코드 전달용이다. Backend는 기존 `SafeZipExtractor`로 ZIP을 검사하고 선별한
안전한 파일만 AI Service에 전달한다. ZIP 내부 이미지는 AI 이미지 자료로 사용하지 않으며,
보고서용 스크린샷은 PNG/JPG로 별도 업로드한다. AI Service가 원본 ZIP 보안 검사를 반복하지 않는다.

Backend는 다음 구조의 임시 bundle을 구성하고 Base64가 아닌 `multipart/form-data`로 전송한다.

```text
temporary bundle
├── manifest.json
├── source/...
├── documents/...
└── images/...
```

성공, 실패, 취소 뒤에는 임시 파일을 삭제한다. 구체적인 manifest와 multipart 계약, 파일 수·크기
제한은 Issue #30에서 확정한다.

## 생성과 검증 계약

Frontend → Backend 생성 요청의 최소 필드는 `fileIds`, `metadata`, `instructions`다.
Backend ↔ AI 내부 통신은 MVP에서 환경변수 `AI_INTERNAL_TOKEN`을 사용하는 공유 Secret 방식을
우선하며, 예를 들어 `X-Internal-Token` 헤더로 전달한다.

AI Service와 Backend는 모두 `ReportDocument`를 검증한다. Backend는 Schema 검증에 더해 다음을
확인한다.

- 허용된 section/block 구조, stable ID, block type, 필수 필드
- `image.fileId`가 해당 프로젝트에 존재하는 PNG/JPG인지 여부
- 삭제·누락 파일, 임의 UUID, 다른 프로젝트나 사용자 파일 참조 차단

이 검증은 AI 결과 생성 저장과 사용자 `PUT /api/reports/{reportId}` 수정 저장에 동일하게
적용하는 목표 계약이며 프로젝트·소유권·MIME 검증은 #37에서 구현한다.

목표 AI 내부 오류 코드는 `AI_INVALID_REQUEST`, `AI_FILE_PROCESSING_FAILED`,
`AI_GENERATION_FAILED`, `AI_INVALID_RESPONSE`, `AI_TIMEOUT`, `AI_UNAVAILABLE`의
최소 집합으로 정의한다. Backend 사용자 API는 기존 오류 규칙에 맞춰
`GENERATION_FAILED`, `GENERATION_TIMEOUT`, `AI_SERVICE_UNAVAILABLE`로 변환한다.

현재 Backend의 `AI_SERVICE_ERROR`·`AI_SERVICE_TIMEOUT` 임시 변환은 #30에서 위 단일
매핑과 HTTP status를 확정한 뒤 #34 구현과 함께 교체한다. 세부 생성 JSON Schema는
Issue #30, ReportDocument와 Report envelope는 #31에서 확정한다.

## 저장·편집·PDF 원칙

- Backend는 생성 요청과 Job 상태, 검증된 Report, 선택 템플릿과 표현 설정을 저장한다.
- 자동 저장은 클라이언트의 기대 version 또는 `If-Match`를 전달하고, Backend는 불일치 시
  저장하지 않고 충돌 정보와 `409 Conflict`를 반환하는 목표 계약이다. 이 API는 #32에서 구현한다.
- A4 Preview와 출력 전용 route는 같은 템플릿 표현 규칙을 사용한다.
- 최종 PDF는 사용자 브라우저 print가 아니라 Backend의 고정 Chromium 환경에서 생성한다.

Backend가 관리하는 생성 상태는 `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`를 기본으로 한다.
제품 개선과 장애 분석에는 프로젝트 생성, 파일 업로드, 생성 요청·완료·실패,
보고서 편집, PDF 출력의 비식별 이벤트만 사용한다. 원본 파일, 프롬프트,
개인정보는 이벤트 metadata나 로그에 저장하지 않으며 구체적인 보존·삭제 정책은
Issue #42에서 확정한다.
