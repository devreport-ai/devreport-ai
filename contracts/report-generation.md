# 프로젝트 파일 기반 AI 생성 계약 결정

이 문서는 Issue #30 구현 전에 합의된 계약 경계를 기록한다. 정확한 JSON Schema, multipart part 이름,
파일 수·전송 크기·AI 입력량 제한은 Issue #30에서 확정한다.

> 구현 상태: Frontend → Backend 요청 검증과 JSONB 저장은 #34에서 반영했다. bundle 전달은
> #43, AI 결과의 파일 참조 검증은 #37에서 구현한다.

## Frontend → Backend

목표 생성 요청에는 최소 다음 필드가 필요하다.

```json
{
  "fileIds": ["업로드 파일 UUID"],
  "metadata": {},
  "instructions": "보고서 작성 지시"
}
```

- `fileIds`는 비어 있거나 중복될 수 없고 모두 요청 프로젝트의 유효한 파일이어야 한다.
- 삭제·누락 파일과 다른 프로젝트 파일은 거부한다.
- 디자인용 `templateId`와 `templateVersion`은 생성 요청에 포함하지 않는다.
- 요청 원문은 `generation_jobs.request_document` JSONB에 저장한다.
- 생성 작업이 `PENDING` 또는 `PROCESSING`인 동안에는 같은 프로젝트의 파일 삭제를
  `409 FILE_IN_USE`로 차단한다. 생성 요청과 삭제는 프로젝트 행 잠금으로 직렬화한다.
- 콘텐츠 구조 구분이 필요해지면 디자인 템플릿과 별개인 `reportType` 또는 `contentProfile`을 정의한다.

Backend는 요청 본문과 위 세 필드를 필수로 검증하고 프로젝트의 유효한 실파일만 허용한다.

## Backend → AI Service

AI 생성 입력은 `ZIP`, `MD`, `TXT`, `PNG`, `JPG/JPEG`다. `PDF`와 `DOCX`는 MVP에서 제외한다.

Backend는 업로드 소유권과 상태를 확인하고 `SafeZipExtractor`가 선별한 소스만 사용해 다음 임시
bundle을 만든다.

```text
temporary bundle
├── manifest.json
├── source/...
├── documents/...
└── images/...
```

- ZIP 원본과 ZIP 내부 이미지는 전달하지 않는다.
- `source/`에는 검증·선별된 ZIP 소스, `documents/`에는 MD/TXT를 둔다.
- `images/`에는 별도 업로드한 PNG/JPG를 둔다.
- manifest는 각 전달 파일을 원본 `fileId`, 분류, 상대 경로와 연결해야 한다.
- Base64를 사용하지 않고 `multipart/form-data`로 전달한다.
- 성공, 실패, 취소 뒤 임시 bundle을 삭제한다.

AI Service는 같은 서버 또는 Docker private network에서만 접근할 수 있다. MVP 내부 인증은 환경변수
`AI_INTERNAL_TOKEN`의 공유 Secret을 `X-Internal-Token` 헤더로 전달하는 방식을 우선한다.

## 응답과 검증

AI Service는 HTML이나 단일 Markdown이 아닌 JSON `ReportDocument`를 반환한다. 텍스트 필드는 계약이
허용하는 Markdown 일부를 사용할 수 있지만 디자인과 레이아웃 정보는 포함하지 않는다.

AI Service가 JSON Schema를 검증한 뒤 반환하고 Backend가 다시 다음을 검증한다.

- 허용된 section/block 구조, stable ID, block type, 필수 필드
- `image.fileId`가 요청 프로젝트에 존재하는 PNG/JPG인지 여부
- 삭제·누락 파일, 임의 UUID, 다른 프로젝트나 사용자 파일 참조 여부

이 의미 검증은 AI 생성 결과 저장과 사용자 `PUT /api/reports/{reportId}` 수정 저장에
동일하게 적용해야 한다. 현재는 JSON Schema만 검증하므로 프로젝트·소유권·MIME 검증은
Issue #37에서 모든 Report 저장 경로에 추가한다.

## 목표 오류 변환

| AI Service | Backend 사용자 API |
| --- | --- |
| `AI_INVALID_REQUEST` | 기존 요청 검증 오류 규칙 |
| `AI_FILE_PROCESSING_FAILED` | `GENERATION_FAILED` |
| `AI_GENERATION_FAILED` | `GENERATION_FAILED` |
| `AI_INVALID_RESPONSE` | `GENERATION_FAILED` |
| `AI_TIMEOUT` | `GENERATION_TIMEOUT` |
| `AI_UNAVAILABLE` | `AI_SERVICE_UNAVAILABLE` |

현재 `HttpAiServiceClient`는 연결·5xx를 `AI_SERVICE_ERROR`, timeout을
`AI_SERVICE_TIMEOUT`으로 변환하는 임시 구현이다. 위 표의 단일 매핑과 오류 응답 모양,
HTTP status는 #30에서 확정하고 #34 구현·테스트와 함께 교체한다.
