/**
 * 테스트용 렌더 헬퍼.
 *
 * 화면 컴포넌트는 라우터와 QueryClient 를 전제로 하므로 테스트에서도 같이 감싸야 한다.
 * 테스트마다 새 QueryClient 를 만드는 이유는 캐시가 테스트 사이에 새지 않게 하기 위해서다.
 * 하나를 공유하면 앞 테스트의 응답이 뒤 테스트에 그대로 보여 원인을 찾기 어려운 실패가 난다.
 */
import type { ReactElement, ReactNode } from 'react'
import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

export function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      // 테스트에서 재시도를 켜두면 실패 케이스가 느려지고 타임아웃이 난다.
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

export function renderWithProviders(ui: ReactElement, { route = '/' } = {}) {
  const client = createTestQueryClient()

  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[route]}>{children}</MemoryRouter>
      </QueryClientProvider>
    )
  }

  return { client, ...render(ui, { wrapper: Wrapper }) }
}
