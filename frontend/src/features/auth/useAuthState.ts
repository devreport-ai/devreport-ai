/** 로그인 여부 구독. tokenStore 는 React 밖 값이라 useSyncExternalStore 가 필요하다. */
import { useSyncExternalStore } from 'react'
import { isAuthenticated, subscribe } from '../../lib/auth/tokenStore'

export function useAuthState(): boolean {
  return useSyncExternalStore(subscribe, isAuthenticated)
}
