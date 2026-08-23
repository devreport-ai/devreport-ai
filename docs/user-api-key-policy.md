# 사용자 LLM API Key 처리·보관·삭제 정책

사용자가 본인의 provider API Key를 등록하면 보고서 생성에 그 키와 선택한 모델을 사용한다(#116, #117).
지원 provider·모델은 Backend `ai.models.allowlist`가 기준이며, allowlist에 모델이 없는 provider의 키는
`AI_PROVIDER_UNSUPPORTED`로 거부한다. 현재 allowlist는 Gemini와 Claude(`claude-sonnet-5`, 서버 키 없이
사용자 키로만 실행)다. 이 문서는 키가 어디를 거치고 어떻게 보관·삭제되는지, 운영자가 지켜야 할
Secret 절차를 정리한다.

## 원칙

- 키 원문은 **등록 요청 → 검증 → 암호화** 순간과 **생성 실행 직전 복호화 → AI Service 호출**
  순간에만 메모리에 존재한다.
- DB에는 AES-256-GCM 암호문·nonce·키 버전·끝 4자리 힌트만 저장한다(`user_ai_credentials`).
- 어떤 API도 키 원문을 돌려주지 않는다. 조회 API는 provider·힌트(`****abcd`)·검증 시각만 반환한다.
- 로그·오류 응답·`generation_jobs.request_document`·Frontend 저장소(React Query 캐시,
  localStorage, sessionStorage)에 키 원문을 남기지 않는다.
- 키로 생성한 비용은 사용자의 provider 계정에 청구된다. Frontend 설정 화면과 생성 폼에 고지한다.

## 흐름

| 단계 | 위치 | 처리 |
| --- | --- | --- |
| 등록 | `PUT /api/me/ai-credentials/{provider}` | 길이·문자 검증 → AI Service `POST /internal/ai/credentials/verify`로 provider 검증 → 암호화 저장 |
| 조회 | `GET /api/me/ai-credentials` | 힌트만 반환 |
| 생성 접수 | `POST /api/projects/{id}/generations` | provider·model allowlist 검증, 키 등록 여부로 `key_source`(SERVER/USER) 결정, snapshot 저장 |
| 생성 실행 | `GenerationJobWorker` | `key_source=USER`면 복호화 후 `X-Provider-Api-Key` 헤더로만 AI Service에 전달 |
| 삭제 | `DELETE /api/me/ai-credentials/{provider}` | 행 삭제. 이후 생성은 서버 기본 모델만 가능하며, 이미 PENDING인 작업은 실행 시 `AI_CREDENTIAL_REQUIRED`로 실패한다 |
| 계정 삭제 | `app_users` FK `ON DELETE CASCADE` | 사용자 행과 함께 삭제 |

## 한도

- 사용자 키(BYOK) 생성은 일일 생성 한도를 적용하지 않는다. 비용이 사용자 계정에 청구되기 때문이다.
- 동시 생성 작업 수 제한(`USAGE_LIMITS_GENERATION_CONCURRENT_LIMIT`)은 키 종류와 무관하게 적용한다.
  Backend·AI Service 자원을 보호하기 위한 것이다.
- 사용자 키로 실행한 작업에서 provider가 키를 거부하면 `AI_CREDENTIAL_INVALID`, provider 사용량
  초과는 `PROVIDER_QUOTA_EXCEEDED`로 실패하며 사용자가 키를 교체하거나 잠시 후 재시도해야 한다.
  서버 키로 실행한 작업의 같은 실패는 사용자 잘못이 아니므로 `AI_SERVICE_UNAVAILABLE` /
  `GENERATION_CAPACITY_EXCEEDED`로 기록한다.

## 운영 Secret

| 환경변수 | 용도 |
| --- | --- |
| `AI_CREDENTIAL_MASTER_KEY` | 암호화 마스터 키. Base64로 인코딩한 32바이트. 운영(`prod` 프로파일)에서 필수 |
| `AI_CREDENTIAL_KEY_VERSION` | 새로 저장하는 키에 사용할 마스터 키 버전(기본 1) |

생성:

```bash
openssl rand -base64 32
```

로컬에서 비워 두면 Backend가 기동 시 임시 키를 만들고 경고를 남긴다. 재시작하면 저장된 키를
복호화할 수 없다. 복호화할 수 없는 키는 **등록되지 않은 것으로 취급**한다 — 기본 모델은 서버 키로
계속 동작하고, 다른 모델은 `available=false`가 되며 선택하면 `AI_CREDENTIAL_REQUIRED`로 거부된다.
사용자는 키를 다시 등록하면 된다.

### Rotation 절차

1. 새 키를 만들고 Backend 설정에 **이전 버전 키를 남긴 채** 새 버전을 추가한다.
   `application.yml`의 `ai-credentials.keys` 맵에 `"2": ${AI_CREDENTIAL_MASTER_KEY_V2}`처럼 항목을 늘리고
   `AI_CREDENTIAL_KEY_VERSION=2`로 올린다.
2. 배포 후 새로 등록·교체되는 키는 버전 2로 암호화되고, 기존 행은 버전 1 키로 계속 복호화된다.
3. 기존 사용자에게 키 교체를 안내하거나 일괄 재암호화 작업을 수행한 뒤, 버전 1 행이 남지 않으면
   버전 1 키를 설정에서 제거한다. 버전 1 행이 남은 상태에서 키를 제거하면 해당 사용자의 키는
   미등록으로 취급되어 기본 모델만 서버 키로 동작하며, 다시 등록해야 다른 모델을 쓸 수 있다.
4. 마스터 키 유출이 의심되면 즉시 새 버전으로 전환하고, 영향받은 사용자에게 provider 콘솔에서
   키 자체를 재발급하도록 안내한다.

## 모델 allowlist 관리

allowlist와 서버 기본 모델은 Backend(`application.yml` `ai.models.allowlist`)와 AI Service
(`GEMINI_ALLOWED_MODELS`, `GEMINI_MODEL`, `ANTHROPIC_ALLOWED_MODELS`) 두 곳에 있으며 **같은 값으로 함께 바꿔야 한다**. Backend는
사용자에게 보여주고 접수 시 검증하며, AI Service는 실행 시 최종 검증한다. 한쪽만 바꾸면 접수는
되지만 실행에서 `GENERATION_REQUEST_INVALID`로 실패한다.

## 관련 계약

- API: `contracts/openapi.yaml` — `/api/me/ai-credentials`, `/api/ai/models`, `GenerationRequest.provider/model`
- Backend ↔ AI Service: `contracts/report-generation.md` — `X-Provider-Api-Key`, `/internal/ai/credentials/verify`
