# Contributing

## Branch Strategy

- `main`: 안정 및 배포 버전
- `dev`: 통합 개발 버전
- `feat/{part}/{feature}`: 기능 개발
- `fix/{part}/{feature}`: 오류 수정
- `chore/{feature}`: 설정 및 환경 작업

예시:

- `feat/backend/project-init`
- `feat/frontend/report-editor`
- `feat/ai/report-generator`

## Workflow

1. 최신 `dev`를 가져온다.
2. `dev`에서 작업 브랜치를 생성한다.
3. 작업 완료 후 `dev` 대상 Pull Request를 생성한다.
4. CodeRabbit 리뷰와 팀원 리뷰를 확인한다.
5. 승인 후 Squash Merge한다.
6. 병합된 작업 브랜치는 삭제한다.

## Issue 자동 종료

`dev` 대상 PR 본문에 `Closes #123`, `Fixes #123`, `Resolves #123`과 같은
closing keyword를 작성하면 PR이 `dev`에 병합된 후 해당 Issue가 자동으로
완료 처리된다. 각 keyword의 표준 변형(`close/closes/closed`,
`fix/fixes/fixed`, `resolve/resolves/resolved`)과 선택적 콜론을 대소문자
구분 없이 지원한다.

PR을 병합하지 않고 닫은 경우 Issue는 종료되지 않는다.

## Commit Convention

- `feat(backend): 프로젝트 생성 API 추가`
- `fix(frontend): 보고서 미리보기 오류 수정`
- `refactor(ai): 프롬프트 생성 구조 개선`
- `docs: API 명세 수정`
- `chore: 개발 환경 설정`

## Rules

- `main`, `dev`에 직접 Push하지 않는다.
- 하나의 브랜치에서는 하나의 작업만 수행한다.
- API Key, 비밀번호, 개인정보를 커밋하지 않는다.
- `contracts/` 변경은 AI, Backend, Frontend 담당자가 함께 확인한다.
