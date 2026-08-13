/**
 * 인증이 필요한 바이너리(이미지 등)를 blob URL 로 받는다.
 * <img src> 는 Authorization 헤더를 실을 수 없어서 fetch 를 거쳐야 한다.
 */
import { getAccessToken } from '../auth/tokenStore'
import { refreshSession } from '../auth/session'

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

/** 401 이면 재발급 후 한 번 재시도 — apiFetch 와 같은 규칙. 반환값은 revoke 책임이 호출부에 있다. */
export async function fetchAuthedBlobUrl(path: string): Promise<string> {
  let response = await send(path)
  if (response.status === 401 && (await refreshSession())) {
    response = await send(path)
  }
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`)
  return URL.createObjectURL(await response.blob())
}

function send(path: string): Promise<Response> {
  const token = getAccessToken()
  return fetch(`${BASE_URL}${path}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    credentials: 'include',
  })
}
