/**
 * 세션 갱신 — 401 시 client.ts 가 부른다.
 *
 * fetch 를 직접 쓰는 이유: client.ts 를 부르면 순환 참조 + 무한 재귀 위험.
 * 재발급은 Authorization 불필요, 재시도 금지(Refresh Token 이 1회용 회전).
 */
import { clearTokens, getRefreshToken, setTokens } from './tokenStore'
import type { TokenResponse } from '../contracts/types'

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''
const REFRESH_TIMEOUT_MS = 10_000

/**
 * 진행 중인 재발급 요청(single-flight). Refresh Token 이 1회용이라
 * 동시 재발급은 첫 요청만 성공한다 — 반드시 한 번만 보내고 결과를 공유한다.
 */
let inflight: Promise<boolean> | null = null

/** 토큰 재발급. 동시에 불러도 요청은 한 번. 실패 시 토큰 정리 후 false. */
export function refreshSession(): Promise<boolean> {
  inflight ??= doRefresh().finally(() => {
    inflight = null
  })
  return inflight
}

async function doRefresh(): Promise<boolean> {
  const refreshToken = getRefreshToken()
  if (refreshToken === null) return false

  let response: Response
  try {
    response = await fetch(`${BASE_URL}/api/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ refreshToken }),
      signal: AbortSignal.timeout(REFRESH_TIMEOUT_MS),
    })
  } catch {
    // 네트워크 실패도 정리한다. 갱신 실패면 이어지는 요청이 전부 401 이다.
    clearTokens()
    return false
  }

  if (!response.ok) {
    clearTokens()
    return false
  }

  try {
    const body = (await response.json()) as TokenResponse
    setTokens(body)
    return true
  } catch {
    clearTokens()
    return false
  }
}
