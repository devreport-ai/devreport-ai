/**
 * Access Token 보관함 — 메모리에만 둔다.
 *
 * Refresh Token 은 #79 부터 HttpOnly 쿠키라 프론트 코드가 만질 수 없다(만지면 안 되는 것이
 * 설계다). 새로고침 복원은 저장이 아니라 앱 시작 시 refresh 호출로 한다 (AuthBootstrap).
 * subscribe 는 useSyncExternalStore 용.
 */

let accessToken: string | null = null
const listeners = new Set<() => void>()

function notify(): void {
  for (const listener of listeners) listener()
}

/** 로그인·재발급 성공 시 호출한다. */
export function setAccessToken(token: string): void {
  accessToken = token
  notify()
}

/** 로그아웃·재발급 실패 시 호출한다. */
export function clearAccessToken(): void {
  if (accessToken === null) return
  accessToken = null
  notify()
}

/** Authorization 헤더에 실을 값. 없으면 null. */
export function getAccessToken(): string | null {
  return accessToken
}

/** 로그인 상태인가. */
export function isAuthenticated(): boolean {
  return accessToken !== null
}

/** 변경 알림 구독. 반환값은 해제 함수. */
export function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}
