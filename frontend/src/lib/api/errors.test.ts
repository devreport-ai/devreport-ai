/** 429(사용량 제한) 표시 — 서버 message 에 재시도 시점을 덧붙인다 (#80 계약). */
import { describe, expect, it } from 'vitest'
import { ApiError, retryAfterSeconds, toDisplayMessage } from './errors'

function rateLimited(details: unknown): ApiError {
  return new ApiError(429, {
    code: 'RATE_LIMIT_EXCEEDED',
    message: '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.',
    details,
    timestamp: '2026-08-13T00:00:00+09:00',
  })
}

describe('toDisplayMessage — 429', () => {
  it('retryAfterSeconds 가 있으면 재시도 시점을 덧붙인다', () => {
    expect(toDisplayMessage(rateLimited({ limit: 10, retryAfterSeconds: 42 }))).toBe(
      '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요. (약 42초 후 가능)',
    )
  })

  it('60초 이상은 분으로 보여준다', () => {
    expect(toDisplayMessage(rateLimited({ retryAfterSeconds: 90 }))).toContain('약 2분 후')
  })

  it('details 가 없으면 서버 문구만 보여준다', () => {
    expect(toDisplayMessage(rateLimited(null))).toBe(
      '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.',
    )
  })

  it('429 가 아니면 덧붙이지 않는다', () => {
    const error = new ApiError(400, {
      code: 'X',
      message: '실패',
      details: { retryAfterSeconds: 5 },
      timestamp: '2026-08-13T00:00:00+09:00',
    })
    expect(retryAfterSeconds(error)).toBeNull()
    expect(toDisplayMessage(error)).toBe('실패')
  })
})
