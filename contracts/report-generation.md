# 프로젝트 파일 기반 AI 생성 계약 결정

이 문서는 프로젝트 파일 기반 AI 보고서 생성의 서비스 간 계약을 기록한다.

> 구현 상태: Frontend → Backend 요청 검증과 JSONB 저장은 #34, bundle 전달은 #43,
> AI 결과의 파일 참조 검증은 #37, AI 오류 변환은 #60에서 반영했다. `metadata` 내부 필드
> 검증은 아직 구현하지 않았다. Backend는 `Map<String, Object>`로, AI Service는
> `dict[str, Any]`로 받아 `{}`도 통과하므로 런타임이 계약을 강제하지 않는다.

## Frontend → Backend

목표 생성 요청에는 최소 다음 필드가 필요하다.

```json
{
  "fileIds": ["업로드 파일 UUID"],
  "metadata": {
    "title": "보고서 제목",
    "author": "작성자",
    "course": "과목",
    "date": "2026-08-12"
  },
  "instructions": "보고서 작성 지시"
}
```

- `fileIds`는 비어 있거나 중복될 수 없고 모두 요청 프로젝트의 유효한 파일이어야 한다.
- 삭제·누락 파일과 다른 프로젝트 파일은 거부한다.
- `metadata`는 `ReportDocument.metadata`와 같은 형태다. `title`은 필수이고 `author`·`course`·`date`는 선택이며 그 밖의 키는 허용하지 않는다.
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

### Bundle manifest

`manifest.json` 형식은 다음과 같다.

```json
{
  "version": 1,
  "files": [
    {
      "fileId": "업로드 파일 UUID",
      "category": "source",
      "path": "source/{fileId}/src/main/App.java",
      "mimeType": "text/plain",
      "size": 1234
    }
  ]
}
```

- `category`는 `source`, `documents`, `images` 중 하나다.
- `path`는 bundle 루트 기준 `/` 구분 상대 경로이며 multipart 파일 이름과 일치한다.
- 하나의 ZIP에서 나온 모든 파일은 같은 원본 ZIP의 `fileId`를 사용한다.
- `size`는 multipart로 전달하는 실제 파일의 바이트 크기다.
- manifest 순서와 반복 `files` part 순서는 같다.

### Multipart

`POST /internal/ai/reports/generate`는 다음 part를 사용한다.

| Part | Content-Type | 내용 |
| --- | --- | --- |
| `request` | `application/json` | `fileIds`, `metadata`, `instructions` |
| `manifest` | `application/json` | `manifest.json` |
| `files` | 각 파일 MIME | 파일별 반복 part, `filename`은 manifest의 `path` |

요청은 `X-Internal-Token: ${AI_INTERNAL_TOKEN}` 헤더로 인증한다. Backend는 토큰이 없으면
AI 호출을 수행하지 않고, AI Service는 헤더가 없거나 값이 다르면 `401 AI_UNAUTHORIZED`를
반환한다. 인증 실패 응답에는 토큰·요청 원문·비밀값을 포함하지 않는다.

### 전송 제한

- 업로드 단계의 개별 파일 제한 20 MiB를 그대로 적용한다.
- bundle 전체 파일은 최대 1,000개, 실제 파일 합계는 최대 100 MiB다.
- ZIP 원본, ZIP 내부의 비분석 파일·이미지, 별도 업로드된 PDF·DOCX는 manifest와 multipart에서 제외한다.
- 제한 초과나 bundle 파일 처리 실패는 생성 작업 실패로 기록한다.

AI Service는 같은 서버 또는 Docker private network에서만 접근할 수 있다. MVP 내부 인증은 환경변수
`AI_INTERNAL_TOKEN`의 공유 Secret을 `X-Internal-Token` 헤더로 전달하는 방식을 우선한다.

## 응답과 검증

AI Service는 HTML이나 단일 Markdown이 아닌 JSON `ReportDocument`를 반환한다. 텍스트 필드는 계약이
허용하는 Markdown 일부를 사용할 수 있지만 디자인과 레이아웃 정보는 포함하지 않는다.

AI Service는 반환 전에 다음을 방어적으로 처리한다.

- `metadata`는 모델 응답을 신뢰하지 않고 생성 요청의 값으로 덮어쓴다.
- manifest `files`가 비어 있으면 `400 AI_INVALID_REQUEST`로 거부한다.
- 정규화 후 분석 근거가 하나도 남지 않으면 `422 AI_FILE_PROCESSING_FAILED`로 거부한다.
  근거 없이 지시문만으로 보고서를 만들지 않기 위한 규칙이다.

AI Service가 JSON Schema를 검증한 뒤 반환하고 Backend가 다시 다음을 검증한다.

- 허용된 section/block 구조, stable ID, block type, 필수 필드
- `image.fileId`가 요청 프로젝트에 존재하는 PNG/JPG인지 여부
- 삭제·누락 파일, 임의 UUID, 다른 프로젝트나 사용자 파일 참조 여부

Frontend는 Report 응답의 `projectId`와 이미지 블록의 `fileId`를 조합해
`GET /api/projects/{projectId}/files/{fileId}`로 이미지를 조회한다.

이 의미 검증은 AI 생성 결과 저장과 사용자 `PUT /api/reports/{reportId}` 수정 저장에
동일하게 적용해야 한다. Backend의 `ReportService.create`와 `ReportService.update`는
`requireValid`를 통해 ReportDocument JSON Schema와 이미지 `fileId`의 존재,
프로젝트 소유권, `image/jpeg`·`image/png` MIME, 저장 파일 정합성을 함께 검증한다.
`ReportIntegrationTest.rejectsInvalidImageReferencesOnCreateAndUpdate`가 이 동작을 회귀 검증한다.

## 목표 오류 변환

| AI Service | Backend 사용자 API |
| --- | --- |
| `AI_UNAUTHORIZED` | `AI_SERVICE_UNAVAILABLE` |
| `AI_INVALID_REQUEST` | `GENERATION_REQUEST_INVALID` |
| `AI_FILE_PROCESSING_FAILED` | `GENERATION_FAILED` |
| `AI_GENERATION_FAILED` | `GENERATION_FAILED` |
| `AI_INVALID_RESPONSE` | `GENERATION_FAILED` |
| `AI_TIMEOUT` | `GENERATION_TIMEOUT` |
| `AI_UNAVAILABLE` | `AI_SERVICE_UNAVAILABLE` |

`HttpAiServiceClient`는 AI Service의 공통 오류 응답에서 `code`만 읽어 위 표로 변환한다.
원격 `message`·`details`는 내부 정보일 수 있으므로 Backend 응답과 로그에 전달하지 않는다.
알 수 없거나 JSON으로 해석할 수 없는 오류 응답은 `GENERATION_FAILED`로 처리한다.

## 운영 보안 설정

Backend는 `SPRING_PROFILES_ACTIVE=prod`에서 `DATABASE_PASSWORD`, `JWT_SECRET`,
`AI_INTERNAL_TOKEN`, `EXPORT_PRINT_URL`이 비어 있으면 기동하지 않으며 `AI_SERVICE_MOCK=true`를
허용하지 않는다. AI Service는 `APP_ENV=prod`에서 `AI_INTERNAL_TOKEN`, `GEMINI_API_KEY`가
필수이고 `MOCK_REPORT=true`를 허용하지 않는다.

Frontend는 Backend와 동일 Origin으로 제공하는 것을 기본으로 한다. cross-origin이 불가피한
경우에만 `CORS_ALLOWED_ORIGINS`에 명시적 allowlist를 설정하며, AI Service는 같은 서버 또는
private network에 두고 외부 포트를 공개하지 않는다.

## 사용량 보호

Backend는 AI 호출 전에 사용자별 일일 생성 횟수와 동시 생성 작업 수를 검사한다.
검사와 `generation_jobs` 저장은 같은 트랜잭션에서 사용자 행 잠금으로 직렬화한다.
제한 초과는 다음 공통 오류로 반환하며, 이때 `GenerationJob`이나 AI 임시 bundle을
만들지 않는다.

| 상황 | HTTP | 오류 코드 |
| --- | --- | --- |
| 일일 생성 횟수 초과 | 429 | `GENERATION_DAILY_LIMIT_EXCEEDED` |
| 동시 생성 작업 초과 | 429 | `GENERATION_CONCURRENCY_LIMIT_EXCEEDED` |
| 사용자 rate limit 초과 | 429 | `RATE_LIMIT_EXCEEDED` |

| 운영 기본값 | 값 |
| --- | --- |
| 사용자별 일일 생성 | 10회 |
| 사용자별 동시 생성 | 2개 |
| 생성 rate limit | 1분당 10회 |

기본값과 운영 quota는 Backend의 `USAGE_LIMITS_*` 환경변수로 조정한다. 생성 요청·성공·실패
수는 `usage_events`에서 집계하고, 실패율은 `failed / (completed + failed)`, 초기 비용
추정은 `requests * 0.05 USD`로 계산한다.
기본 일일 상한의 80% 또는 실패율 20% 초과를 알림 기준으로 사용하며, 실제 Gemini
토큰 사용량을 연동하는 것은 별도 작업이다.
