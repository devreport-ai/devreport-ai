/**
 * TanStack Query 전역 설정.
 *
 * 서버 데이터를 직접 `useState` + `useEffect` 로 다루지 않는 이유는 폴링 때문이다.
 * 생성 작업 상태를 2~3초마다 조회해야 하는데, 정리(cleanup)·중복 요청 방지·
 * StrictMode 이중 실행 같은 함정을 직접 처리하면 틀리기 쉽다.
 */
import { QueryClient } from '@tanstack/react-query'
import { ApiError } from './api/errors'

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      /**
       * 4xx 는 다시 요청해도 결과가 같다. 없는 프로젝트를 세 번 더 물어볼 이유가 없다.
       * 서버 오류(5xx)와 연결 실패(status 0)만 한 번 더 시도한다.
       */
      retry: (failureCount, error) => {
        if (error instanceof ApiError && error.status >= 400 && error.status < 500) return false
        return failureCount < 1
      },
      // 화면을 다시 볼 때마다 자동으로 다시 부르지 않는다. 필요한 곳에서 명시적으로 갱신한다.
      refetchOnWindowFocus: false,
    },
    mutations: {
      // 생성 요청·업로드 같은 쓰기 작업은 자동 재시도하지 않는다. 중복 생성이 날 수 있다.
      retry: false,
    },
  },
})
