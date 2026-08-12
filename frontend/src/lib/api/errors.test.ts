/**
 * 공통 에러 응답 판별 테스트.
 *
 * `isErrorResponse` 가 느슨하면, 타입은 `timestamp: string` 이라고 선언해 놓고
 * 실제로는 undefined 가 담긴 객체가 통과한다. 계약 필수 필드 세 개를 모두
 * 확인하는지 고정해 둔다. (PR #55 리뷰 지적)
 */
import { describe, expect, it } from 'vitest'
import { isErrorResponse, toFallbackErrorResponse } from './errors'

describe('isErrorResponse', () => {
  const valid = {
    code: 'FILE_TOO_LARGE',
    message: '파일이 너무 큽니다.',
    details: null,
    timestamp: '2026-08-12T09:00:00+09:00',
  }

  it('계약 필수 필드를 모두 갖추면 통과한다', () => {
    expect(isErrorResponse(valid)).toBe(true)
  })

  it('timestamp 가 없으면 거부한다', () => {
    expect(isErrorResponse({ code: valid.code, message: valid.message, details: null })).toBe(false)
  })

  it('timestamp 가 문자열이 아니면 거부한다', () => {
    expect(isErrorResponse({ ...valid, timestamp: 1_754_000_000 })).toBe(false)
  })

  it('code 나 message 가 문자열이 아니면 거부한다', () => {
    expect(isErrorResponse({ ...valid, code: 500 })).toBe(false)
    expect(isErrorResponse({ ...valid, message: null })).toBe(false)
  })

  it('객체가 아닌 값을 거부한다', () => {
    expect(isErrorResponse(null)).toBe(false)
    expect(isErrorResponse('error')).toBe(false)
  })
})

describe('toFallbackErrorResponse', () => {
  it('규격을 갖춘 대체 응답을 만든다', () => {
    const fallback = toFallbackErrorResponse('연결 실패')

    expect(fallback.code).toBe('UNKNOWN_ERROR')
    expect(fallback.message).toBe('연결 실패')
    // 스스로 만든 값도 판별을 통과해야 호출부가 형태를 하나로 다룰 수 있다.
    expect(isErrorResponse(fallback)).toBe(true)
  })
})
