/**
 * 모든 테스트 파일보다 먼저 한 번 실행되는 준비 파일.
 * vite.config.ts 의 test.setupFiles 로 등록되어 있다.
 */

// toBeInTheDocument() 같은 DOM 전용 검증 함수를 expect 에 추가한다.
// 이게 없으면 "expect(...).toBeInTheDocument is not a function" 이 난다.
import '@testing-library/jest-dom/vitest'

import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// 각 테스트가 끝날 때마다 렌더된 DOM 을 지운다.
// 남겨두면 다음 테스트에서 같은 텍스트가 두 번 잡혀 getByText 가 실패한다.
// 원인을 찾기 어려운 실패라서 처음부터 걸어둔다.
afterEach(() => {
  cleanup()
})
