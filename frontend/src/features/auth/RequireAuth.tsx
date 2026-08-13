/**
 * 라우트 보호. 미로그인이면 로그인으로 보내고, 가려던 주소를 state 로 넘긴다.
 * 재발급 실패로 토큰이 비워져도 구독 덕에 자동으로 로그인 화면으로 이동한다.
 */
import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { useAuthState } from './useAuthState'

export function RequireAuth({ children }: { children: ReactNode }) {
  const authenticated = useAuthState()
  const location = useLocation()

  if (!authenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  }

  return children
}
