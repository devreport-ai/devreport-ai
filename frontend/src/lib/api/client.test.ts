/**
 * API Client 동작 테스트.
 *
 * 여기 있는 항목들은 전부 코드 리뷰(PR #55)에서 실제로 지적받은 버그다.
 * lint·typecheck·build 는 이 문제들을 하나도 잡지 못했다. 문법과 타입은
 * 모두 합법이었고 깨진 것은 런타임 동작이었다. 그래서 테스트로 고정한다.
 *
 * `fetch` 는 전역을 갈아끼워 가로챈다. 실제 네트워크를 타지 않으므로
 * Backend 가 떠 있지 않아도 돌아간다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch, apiFetchNoContent } from './client'
import { ApiError } from './errors'

/** 성공 응답을 흉내 낸다. */
function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** 마지막 fetch 호출에 실제로 전달된 헤더를 꺼낸다. */
function lastRequestHeaders(mock: ReturnType<typeof vi.fn>): Headers {
  const init = mock.mock.calls.at(-1)?.[1] as RequestInit
  return init.headers as Headers
}

let fetchMock: ReturnType<typeof vi.fn>

beforeEach(() => {
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('헤더 처리', () => {
  it('Headers 인스턴스로 넘겨도 항목이 사라지지 않는다', async () => {
    // 객체 전개(`{ ...headers }`)로 합치면 Headers 의 항목이 통째로 사라진다.
    // 나중에 Authorization 을 이렇게 넘기면 인증이 조용히 빠진 채 401 만 받게 된다.
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }))

    await apiFetch('/api/test', { headers: new Headers({ Authorization: 'Bearer token' }) })

    expect(lastRequestHeaders(fetchMock).get('Authorization')).toBe('Bearer token')
  })

  it('[key, value] 튜플 배열로 넘겨도 올바른 헤더가 된다', async () => {
    // 객체 전개로는 { "0": [...] } 같은 엉뚱한 헤더가 만들어졌다.
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }))

    await apiFetch('/api/test', { headers: [['X-Trace-Id', 'abc']] })

    const headers = lastRequestHeaders(fetchMock)
    expect(headers.get('X-Trace-Id')).toBe('abc')
    expect(headers.get('0')).toBeNull()
  })

  it('Accept 기본값을 넣되 호출부가 지정하면 덮어쓰지 않는다', async () => {
    // Response 본문은 한 번만 읽을 수 있다. 이 테스트는 요청을 두 번 보내므로
    // 같은 객체를 재사용하지 않고 호출할 때마다 새로 만든다.
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse({ ok: true })))

    await apiFetch('/api/test')
    expect(lastRequestHeaders(fetchMock).get('Accept')).toBe('application/json')

    await apiFetch('/api/test', { headers: { Accept: 'application/pdf' } })
    expect(lastRequestHeaders(fetchMock).get('Accept')).toBe('application/pdf')
  })
})

describe('요청 취소', () => {
  it('호출자가 취소하면 AbortError 를 그대로 던진다', async () => {
    // 화면을 벗어나며 폴링을 정리한 것뿐인데 "서버에 연결할 수 없습니다" 가
    // 뜨면 안 된다. 호출부가 취소와 장애를 구분할 수 있어야 한다.
    fetchMock.mockRejectedValue(new DOMException('Aborted', 'AbortError'))

    await expect(apiFetch('/api/test')).rejects.toSatisfy(
      (error: unknown) => error instanceof DOMException && error.name === 'AbortError',
    )
  })

  it('타임아웃은 ApiError 로 바꿔 사용자에게 보여줄 문구를 담는다', async () => {
    fetchMock.mockRejectedValue(new DOMException('Timed out', 'TimeoutError'))

    await expect(apiFetch('/api/test')).rejects.toBeInstanceOf(ApiError)
  })
})

describe('본문 없는 응답', () => {
  it('apiFetchNoContent 는 204 를 정상 처리한다', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))

    await expect(apiFetchNoContent('/api/projects/1')).resolves.toBeUndefined()
  })

  it('apiFetch 는 204 를 받으면 undefined 를 반환하지 않고 에러를 던진다', async () => {
    // 예전 구현은 `undefined as T` 로 타입을 속였다. 호출부가 아무 검사 없이
    // 속성에 접근하다 런타임에 터질 수 있었다.
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))

    await expect(apiFetch('/api/projects/1')).rejects.toBeInstanceOf(ApiError)
  })
})

describe('에러 응답 해석', () => {
  it('계약 형식의 실패 응답에서 code 를 꺼낸다', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(
        {
          code: 'PROJECT_NOT_FOUND',
          message: '프로젝트를 찾을 수 없습니다.',
          details: null,
          timestamp: '2026-08-12T09:00:00+09:00',
        },
        404,
      ),
    )

    const error = await apiFetch('/api/projects/1').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe('PROJECT_NOT_FOUND')
    expect((error as ApiError).status).toBe(404)
  })

  it('규격을 벗어난 실패 응답도 ApiError 로 감싼다', async () => {
    // 프록시가 HTML 오류 페이지를 대신 돌려주는 경우가 실제로 있다.
    fetchMock.mockResolvedValue(new Response('<html>502</html>', { status: 502 }))

    const error = await apiFetch('/api/projects').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe('UNKNOWN_ERROR')
  })
})
