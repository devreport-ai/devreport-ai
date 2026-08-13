/**
 * 세션 갱신(쿠키 방식, #79) 테스트.
 * single-flight 와 "429 는 로그아웃 아님" 규칙이 핵심이다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { refreshSession } from './session'
import { clearAccessToken, getAccessToken, setAccessToken } from './tokenStore'

function tokenJson(access: string) {
  return new Response(
    JSON.stringify({ accessToken: access, tokenType: 'Bearer', expiresIn: 900 }),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
  )
}

function errorJson(status: number, code: string) {
  return new Response(
    JSON.stringify({
      code,
      message: '실패',
      details: null,
      timestamp: '2026-08-13T00:00:00+09:00',
    }),
    { status, headers: { 'Content-Type': 'application/json' } },
  )
}

beforeEach(() => {
  setAccessToken('old-access')
})

afterEach(() => {
  clearAccessToken()
  vi.unstubAllGlobals()
})

describe('refreshSession', () => {
  it('본문 없이 쿠키 동봉으로 호출하고 새 Access Token 을 저장한다', async () => {
    const fetchMock = vi.fn(() => Promise.resolve(tokenJson('new-access')))
    vi.stubGlobal('fetch', fetchMock)

    await expect(refreshSession()).resolves.toBe(true)
    expect(getAccessToken()).toBe('new-access')

    // 계약(#79): Refresh Token 은 쿠키다. 본문을 보내면 안 되고 credentials 가 필요하다.
    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(init.body).toBeUndefined()
    expect(init.credentials).toBe('include')
  })

  it('동시에 여러 번 불러도 요청은 한 번만 나간다', async () => {
    const fetchMock = vi.fn(() => Promise.resolve(tokenJson('new-access')))
    vi.stubGlobal('fetch', fetchMock)

    const results = await Promise.all([refreshSession(), refreshSession(), refreshSession()])

    expect(results).toEqual([true, true, true])
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('앞선 재발급이 끝난 뒤에는 새로 요청할 수 있다', async () => {
    const fetchMock = vi.fn(() => Promise.resolve(tokenJson('a')))
    vi.stubGlobal('fetch', fetchMock)

    await refreshSession()
    await refreshSession()

    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('쿠키가 무효(401)면 토큰을 정리하고 false 를 준다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(errorJson(401, 'INVALID_REFRESH_TOKEN'))),
    )

    await expect(refreshSession()).resolves.toBe(false)
    expect(getAccessToken()).toBeNull()
  })

  it('429(요청 한도)면 토큰을 지우지 않는다 — 세션이 로그아웃되면 안 된다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(errorJson(429, 'RATE_LIMIT_EXCEEDED'))),
    )

    await expect(refreshSession()).resolves.toBe(false)
    // 토큰이 살아 있어야 제한 창이 지난 뒤 다음 401 에서 재발급이 다시 시도된다
    expect(getAccessToken()).toBe('old-access')
  })

  it('네트워크가 끊기면 토큰을 정리하고 false 를 준다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))),
    )

    await expect(refreshSession()).resolves.toBe(false)
    expect(getAccessToken()).toBeNull()
  })
})
