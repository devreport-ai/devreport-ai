/**
 * ESLint 설정 (Flat Config).
 *
 * ESLint 9 부터는 `.eslintrc.*` 대신 이 파일 하나로 설정한다.
 * 배열에 넣은 순서대로 규칙이 겹쳐 쌓이며, 뒤에 온 것이 앞의 것을 덮는다.
 * 그래서 `prettier` 를 반드시 맨 마지막에 둔다(아래 설명 참고).
 *
 * Vite 템플릿 기본값은 oxlint 였으나, 팀 이슈 #50 기준과 자료 접근성을 이유로
 * ESLint + Prettier 조합을 선택했다.
 */
import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import prettier from 'eslint-config-prettier'

export default tseslint.config(
  // 빌드 산출물은 우리가 쓴 코드가 아니므로 검사 대상에서 제외한다.
  { ignores: ['dist'] },

  // 자바스크립트 기본 권장 규칙 (미사용 변수, 중복 선언 등).
  js.configs.recommended,

  // TypeScript 전용 규칙. 위의 JS 규칙 중 TS 에서 중복되거나 오탐인 것을 알아서 꺼준다.
  ...tseslint.configs.recommended,

  // React Hooks 규칙. useEffect 의존성 누락처럼 눈으로는 못 잡는 버그를 걸러낸다.
  // 초보가 가장 많이 넘어지는 지점이라 반드시 켠다.
  //
  // 이 플러그인은 구형(eslintrc)과 신형(flat) 설정을 둘 다 제공한다.
  // `configs['recommended-latest']` 는 구형이라 plugins 가 배열이어서 ESLint 10 이 거부한다.
  // flat config 에서는 반드시 `configs.flat.recommended` 를 써야 한다.
  reactHooks.configs.flat.recommended,

  // 개발 중 화면이 새로고침 없이 갱신되는 기능(Fast Refresh)이 깨지는 패턴을 경고한다.
  // 'vite' 프리셋은 Vite 프로젝트 구조에 맞게 조정된 버전이다.
  reactRefresh.configs.vite,

  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2023,
      // 브라우저에서 도는 코드이므로 window·document 등을 전역으로 인정한다.
      // 이게 없으면 'document is not defined' 같은 오탐이 쏟아진다.
      globals: globals.browser,
    },
  },

  // 반드시 맨 마지막.
  // 들여쓰기·따옴표·줄바꿈 같은 "생김새" 규칙은 Prettier 가 전담하므로,
  // ESLint 쪽 동일 규칙을 전부 꺼서 둘이 서로 다투지 않게 한다.
  prettier,
)
