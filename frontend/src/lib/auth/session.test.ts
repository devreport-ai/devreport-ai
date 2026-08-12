/**
 * 세션 갱신 테스트.
 *
 * 핵심은 single-flight 다. 서버가 Refresh Token 을 1회용으로 회전시키므로
 * 동시에 두 번 재발급을 보내면 두 번째가 반드시 실패해 로그아웃된다.
 * "여러 곳이 동시에 401 을 받아도 재발급 요청은 한 번" 을 여기서 고정한다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { refreshSession } from './session'
import { clearTokens, getAccessToken, getRefreshToken, setTokens } from './tokenStore'

function tokenJson(access: string, refresh: string) {
  return new Response(
    JSON.stringify({
      accessToken: access,
      refreshToken: refresh,
      tokenType: 'Bearer',
      expiresIn: 900,
    }),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
  )
}

beforeEach(() => {
  setTokens({ accessToken: 'old-access', refreshToken: 'old-refresh' })
})

afterEach(() => {
  clearTokens()
  vi.unstubAllGlobals()
})

describe('refreshSession', () => {
  it('성공하면 새 토큰 쌍으로 바꾼다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(tokenJson('new-access', 'new-refresh'))),
    )

    await expect(refreshSession()).resolves.toBe(true)
    expect(getAccessToken()).toBe('new-access')
    // 서버가 토큰을 회전시키므로 Refresh Token 도 새것으로 바뀌어야 한다.
    // 옛것을 들고 있으면 다음 재발급이 INVALID_REFRESH_TOKEN 으로 실패한다.
    expect(getRefreshToken()).toBe('new-refresh')
  })

  it('동시에 여러 번 불러도 요청은 한 번만 나간다', async () => {
    const fetchMock = vi.fn(() => Promise.resolve(tokenJson('new-access', 'new-refresh')))
    vi.stubGlobal('fetch', fetchMock)

    const results = await Promise.all([refreshSession(), refreshSession(), refreshSession()])

    expect(results).toEqual([true, true, true])
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('앞선 재발급이 끝난 뒤에는 새로 요청할 수 있다', async () => {
    const fetchMock = vi.fn(() => Promise.resolve(tokenJson('a', 'b')))
    vi.stubGlobal('fetch', fetchMock)

    await refreshSession()
    await refreshSession()

    // single-flight 는 "동시" 만 합친다. 순차 호출까지 막으면 두 번째 만료를 못 푼다.
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('서버가 401 을 주면 토큰을 정리하고 false 를 준다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({
              code: 'INVALID_REFRESH_TOKEN',
              message: 'Refresh Token이 유효하지 않습니다.',
              details: null,
              timestamp: '2026-08-12T00:00:00+09:00',
            }),
            { status: 401, headers: { 'Content-Type': 'application/json' } },
          ),
        ),
      ),
    )

    await expect(refreshSession()).resolves.toBe(false)
    // 토큰을 남겨두면 이어지는 모든 요청이 401 을 반복한다. 확실히 로그아웃 상태로 만든다.
    expect(getAccessToken()).toBeNull()
  })

  it('네트워크가 끊겨도 토큰을 정리하고 false 를 준다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))),
    )

    await expect(refreshSession()).resolves.toBe(false)
    expect(getAccessToken()).toBeNull()
  })

  it('Refresh Token 이 없으면 요청 없이 false 를 준다', async () => {
    clearTokens()
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    await expect(refreshSession()).resolves.toBe(false)
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
