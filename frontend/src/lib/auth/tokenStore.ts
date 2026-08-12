/**
 * 토큰 보관함 — 토큰을 읽고 쓰는 유일한 통로.
 *
 * 지금은 메모리에만 둔다(새로고침 = 로그아웃). 보관 위치는 팀 결정 대기 중이며,
 * 결정 나면 이 파일만 바뀐다. subscribe 는 useSyncExternalStore 용.
 */
import type { TokenResponse } from '../contracts/types'

/** 보관하는 토큰 쌍. null 이면 로그아웃 상태다. */
export interface StoredTokens {
  accessToken: string
  refreshToken: string
}

let tokens: StoredTokens | null = null
const listeners = new Set<() => void>()

function notify(): void {
  for (const listener of listeners) listener()
}

/** 로그인·재발급 성공 시 호출한다. */
export function setTokens(response: Pick<TokenResponse, 'accessToken' | 'refreshToken'>): void {
  tokens = { accessToken: response.accessToken, refreshToken: response.refreshToken }
  notify()
}

/** 로그아웃·재발급 실패 시 호출한다. */
export function clearTokens(): void {
  if (tokens === null) return
  tokens = null
  notify()
}

/** Authorization 헤더에 실을 값. 없으면 null. */
export function getAccessToken(): string | null {
  return tokens?.accessToken ?? null
}

/** 재발급·로그아웃 요청 본문에 실을 값. 없으면 null. */
export function getRefreshToken(): string | null {
  return tokens?.refreshToken ?? null
}

/** 로그인 상태인가. */
export function isAuthenticated(): boolean {
  return tokens !== null
}

/** 변경 알림 구독. 반환값은 해제 함수. */
export function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}
