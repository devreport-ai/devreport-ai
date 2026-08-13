# DevReport AI — Frontend

React + Vite + TypeScript 기반 SPA.

## 요구 사항

| 항목    | 버전                      |
| ------- | ------------------------- |
| Node.js | 24 LTS (`.nvmrc` 에 고정) |
| npm     | Node 24 에 포함된 버전    |

`fnm` 이나 `nvm` 을 쓰면 `.nvmrc` 를 읽어 자동으로 버전을 맞춰 준다.

```bash
fnm use      # 또는 nvm use
node -v      # v24.x 확인
```

## 실행

```bash
cd frontend
npm ci          # package-lock.json 기준 설치. npm install 대신 이걸 쓴다.
npm run dev     # http://localhost:3000
```

포트는 3000 으로 고정되어 있으며, 이미 사용 중이면 **다른 포트로 옮겨가지 않고 실패한다.**
팀 규격(루트 `.env.example` 의 `FRONTEND_PORT=3000`)과 아래 프록시 설정이 어긋나는 것을
막기 위한 의도적인 동작이다.

## 스크립트

| 명령                   | 설명                                 |
| ---------------------- | ------------------------------------ |
| `npm run dev`          | 개발 서버 (포트 3000)                |
| `npm run build`        | 타입 검사 후 프로덕션 빌드 → `dist/` |
| `npm run preview`      | 빌드 결과물을 로컬에서 확인          |
| `npm run typecheck`    | 타입 검사만 수행                     |
| `npm run lint`         | ESLint 검사                          |
| `npm run format`       | Prettier 로 코드 정렬                |
| `npm run format:check` | 정렬 여부만 확인 (고치지 않음)       |
| `npm test`             | Vitest 실행                          |

커밋 전에는 `lint` → `typecheck` → `test` → `build` 를 통과시킨다.

## 환경변수

실제 값은 `.env.local` 에 넣는다. 이 파일은 루트 `.gitignore` 의 `.env.*` 규칙으로
커밋되지 않는다. 커밋되는 것은 `.env.example` 뿐이다.

```bash
cp .env.example .env.local
```

| 이름                | 설명                                              |
| ------------------- | ------------------------------------------------- |
| `VITE_API_BASE_URL` | Backend API 주소. **개발 중에는 빈 값으로 둔다.** |

> **주의**
> Vite 는 `VITE_` 로 시작하는 값만 브라우저 코드에 노출하며, 그 값은 빌드 결과물에
> 그대로 포함되어 누구나 열어볼 수 있다. API Key·토큰·비밀번호를 넣지 않는다.

## Backend 연동과 CORS

Backend는 `CORS_ALLOWED_ORIGINS`에 등록된 Origin에만 credentials 요청을 허용한다.
개발 중에는 Vite 개발 서버의 프록시로 우회할 수 있다.

```text
브라우저 → localhost:3000/api/...  →  (Vite 프록시)  →  localhost:8080/api/...
```

브라우저 입장에서는 같은 오리진이므로 CORS 가 발생하지 않는다.
그래서 `VITE_API_BASE_URL` 을 비워 두고 `/api/...` 로 호출하면 된다.

**이 우회는 Vite 개발 서버 전용이다.** 별도 Origin으로 배포하면 Vite 개발 프록시가
포함되지 않으므로 Frontend에는 Backend Origin을, Backend에는 정확한 Frontend Origin을
설정해야 한다.

```bash
VITE_API_BASE_URL=https://api.example.com
CORS_ALLOWED_ORIGINS=https://app.example.com
```

운영 배포는 reverse proxy로 Frontend와 Backend를 동일 Origin에 제공하는 것을 기본으로
한다. 별도 Origin이 불가피한 경우에만 Backend에 정확한 Origin allowlist를 설정한다.

Backend 를 함께 띄우려면 저장소 루트에서:

```bash
cd backend && ./gradlew bootRun     # http://localhost:8080
```

## 디렉터리 구조

```text
frontend/
├── src/
│   ├── main.tsx           진입점. 라우터로 감싸고 전역 스타일을 불러온다.
│   ├── App.tsx            라우트 표
│   ├── routes/            페이지 컴포넌트
│   ├── lib/api/           API Client — HTTP 호출은 전부 여기를 거친다
│   └── test/setup.ts      테스트 준비 파일
├── vite.config.ts         포트·프록시·테스트 설정
├── eslint.config.js
└── .nvmrc
```

### 규칙

- **컴포넌트에서 `fetch` 를 직접 부르지 않는다.** 반드시 `src/lib/api/` 를 거친다.
  공통 처리(타임아웃, 에러 응답 해석, 이후 추가될 인증 토큰)를 한 곳에서만 고치기 위해서다.
- API 주소는 하드코딩하지 않고 환경변수를 쓴다.

## 이번 범위에 포함하지 않은 것

이슈 [#50](https://github.com/devreport-ai/devreport-ai/issues/50) 기준으로 후속 이슈에서 진행한다.

- 인증 토큰 주입과 401 재발급 — 로그인 화면이 없어 검증할 수 없다
- `contracts/openapi.yaml` 기반 타입 생성 — 계약이 아직 변경 중이다
- 실제 화면 구현 — 이슈 #36, #38, #39

CI 워크플로(`.github/workflows/frontend-ci.yml`)는 이 작업에 함께 포함했다.
`frontend/**` 가 바뀔 때만 `npm ci` → lint → typecheck → test → build 를 실행한다.
