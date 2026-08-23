/**
 * Vite 개발 서버 · 빌드 · 테스트 설정.
 *
 * 이 프로젝트에서 이 파일이 특별히 중요한 이유는 두 가지다.
 *
 * 1) 팀이 정한 Frontend 포트(3000)를 강제한다.
 *    루트 `.env.example` 의 FRONTEND_PORT=3000 과 맞춘 값이다.
 *
 * 2) 개발 중 CORS 문제를 프록시로 우회한다.
 *    Backend 에도 허용 Origin과 credentials 설정이 있지만, 개발에서는
 *    브라우저가 localhost:3000 단일 오리진으로 인식하도록 프록시를 쓴다.
 *
 * `defineConfig` 를 'vite' 가 아니라 'vitest/config' 에서 가져오는 이유는
 * 아래 test 블록에 타입을 붙이기 위해서다. Vite 쪽 defineConfig 는 test 키를 모른다.
 */
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  // Tailwind v4 는 PostCSS 설정 파일 없이 Vite 플러그인으로 붙인다.
  // 클래스 스캔 대상도 자동으로 잡으므로 content 설정이 필요 없다.
  plugins: [react(), tailwindcss()],

  server: {
    // Vite 기본 포트는 5173 이다. 팀 규격이 3000 이므로 명시적으로 고정한다.
    // 3000 은 루트 `.env.example` 의 FRONTEND_PORT 로 팀이 정한 값이다.
    port: 3000,

    // 3000 이 이미 점유됐을 때 Vite 는 기본적으로 조용히 다른 포트로 옮겨간다.
    // 그러면 프록시 주소도 어긋나서 "왜 API 호출이 안 되지" 같은 원인 찾기
    // 어려운 문제가 생긴다. 차라리 즉시 실패하도록 막는다.
    strictPort: true,

    proxy: {
      // 브라우저는 localhost:3000 에만 요청하므로 같은 오리진으로 인식한다.
      // 따라서 CORS preflight 자체가 발생하지 않는다.
      //
      // 주의: 이건 개발 서버 전용이다. 배포하면 이 프록시가 없으므로
      // Backend 쪽 CORS 설정이 반드시 필요하다.
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },

  test: {
    // React 컴포넌트는 document·window 같은 브라우저 API 를 쓴다.
    // Node 환경에는 그게 없으므로 jsdom 으로 흉내 낸다.
    environment: 'jsdom',

    // describe/it/expect 를 매 파일에서 import 하지 않아도 되게 한다.
    // Jest 에 익숙한 사람이 그대로 코드를 옮겨 쓸 수 있다는 이점도 있다.
    globals: true,

    // @testing-library/jest-dom 의 matcher 등록과 테스트 후 정리를 담당한다.
    setupFiles: './src/test/setup.ts',
  },
})
