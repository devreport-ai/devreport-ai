/**
 * 세션 갱신 — HttpOnly 쿠키의 Refresh Token 으로 새 Access Token 을 받는다 (#79).
 *
 * 쿠키는 브라우저가 알아서 싣는다. 본문 없음.
 * fetch 를 직접 쓰는 이유: client.ts 가 401 에서 이 함수를 부르므로 순환·재귀 방지.
 * single-flight — 서버가 Refresh Token 을 회전시키므로 동시 재발급은 한 번만 보낸다.
 */
import { clearAccessToken, setAccessToken } from './tokenStore'
import type { TokenResponse } from '../contracts/types'

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''
const REFRESH_TIMEOUT_MS = 10_000

let inflight: Promise<boolean> | null = null

/** 토큰 재발급. 동시에 불러도 요청은 한 번. 실패 시 토큰 정리 후 false. */
export function refreshSession(): Promise<boolean> {
  inflight ??= doRefresh().finally(() => {
    inflight = null
  })
  return inflight
}

async function doRefresh(): Promise<boolean> {
  let response: Response
  try {
    response = await fetch(`${BASE_URL}/api/auth/refresh`, {
      method: 'POST',
      headers: { Accept: 'application/json' },
      // 배포에서 API 가 다른 오리진일 수 있어 쿠키 동봉을 명시한다 (CORS 는 Backend 몫)
      credentials: 'include',
      signal: AbortSignal.timeout(REFRESH_TIMEOUT_MS),
    })
  } catch {
    // 네트워크 실패도 정리한다. 갱신 실패면 이어지는 요청이 전부 401 이다.
    clearAccessToken()
    return false
  }

  if (!response.ok) {
    // 429(요청 한도)는 토큰이 무효라는 뜻이 아니다. 지우면 멀쩡한 세션이 로그아웃된다.
    if (response.status !== 429) clearAccessToken()
    return false
  }

  try {
    const body = (await response.json()) as TokenResponse
    setAccessToken(body.accessToken)
    return true
  } catch {
    clearAccessToken()
    return false
  }
}
