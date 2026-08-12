/**
 * 생성 상태 폴링 판단 테스트.
 *
 * 이 판단이 틀리면 증상이 조용하다. 너무 일찍 멈추면 완료됐는데 화면이 "진행 중" 에서
 * 안 바뀌고, 안 멈추면 브라우저가 서버를 영원히 두드린다. 둘 다 lint·build 로는
 * 잡히지 않아 여기서 조건별로 고정한다.
 *
 * 계약 규칙: 지수 백오프 + 타임아웃 상한 필수, 무한 폴링 금지.
 */
import { describe, expect, it } from 'vitest'
import { MAX_POLL_COUNT, nextPollInterval, pollIntervalMs } from './api'

describe('pollIntervalMs', () => {
  it('첫 재조회는 2초다', () => {
    // refetchInterval 은 첫 응답을 받은 뒤에 처음 불리므로 그 시점 count 는 1 이다.
    // 여기서 1 을 빼지 않으면 첫 재조회가 3초가 된다.
    expect(pollIntervalMs(1)).toBe(2000)
  })

  it('1.5배씩 늘어난다', () => {
    expect(pollIntervalMs(2)).toBe(3000)
    expect(pollIntervalMs(3)).toBe(4500)
    expect(pollIntervalMs(4)).toBe(6750)
  })

  it('15초를 넘지 않는다', () => {
    // 상한이 없으면 뒤로 갈수록 간격이 몇 분 단위로 벌어져 완료를 늦게 안다.
    expect(pollIntervalMs(20)).toBe(15000)
    expect(pollIntervalMs(60)).toBe(15000)
  })

  it('첫 호출 전(count 0)에도 음수 지수가 되지 않는다', () => {
    expect(pollIntervalMs(0)).toBe(2000)
  })
})

describe('nextPollInterval', () => {
  it('진행 중이면 다음 간격을 준다', () => {
    expect(nextPollInterval({ error: null, status: 'PROCESSING', dataUpdateCount: 1 })).toBe(2000)
    expect(nextPollInterval({ error: null, status: 'PENDING', dataUpdateCount: 3 })).toBe(4500)
  })

  it('종료 상태에서는 멈춘다', () => {
    // 하나라도 빠지면 끝난 작업을 계속 조회한다.
    for (const status of ['COMPLETED', 'FAILED', 'CANCELED'] as const) {
      expect(nextPollInterval({ error: null, status, dataUpdateCount: 1 })).toBe(false)
    }
  })

  it('오류가 남아 있으면 멈춘다', () => {
    // 재시도까지 실패한 뒤에도 계속 물어보면 같은 실패만 반복한다.
    expect(
      nextPollInterval({ error: new Error('network'), status: 'PROCESSING', dataUpdateCount: 1 }),
    ).toBe(false)
  })

  it('상한에 도달하면 멈춘다', () => {
    expect(
      nextPollInterval({ error: null, status: 'PROCESSING', dataUpdateCount: MAX_POLL_COUNT - 1 }),
    ).toBe(15000)
    expect(
      nextPollInterval({ error: null, status: 'PROCESSING', dataUpdateCount: MAX_POLL_COUNT }),
    ).toBe(false)
  })

  it('아직 응답이 없으면 계속 조회한다', () => {
    // 첫 응답 전에 멈춰 버리면 작업이 시작도 못 한 채로 화면이 굳는다.
    expect(nextPollInterval({ error: null, status: undefined, dataUpdateCount: 0 })).toBe(2000)
  })

  it('상한까지의 총 대기 시간이 10분을 넘는다', () => {
    // 규칙이 타임아웃 상한을 요구한다. 값을 바꿀 때 실제 시간이 얼마인지 드러나게 둔다.
    // count 가 MAX_POLL_COUNT 면 nextPollInterval 이 false 라 그 간격은 예약되지 않는다.
    let total = 0
    for (let count = 1; count < MAX_POLL_COUNT; count += 1) total += pollIntervalMs(count)
    expect(total).toBeGreaterThan(10 * 60 * 1000)
    expect(total).toBeLessThan(20 * 60 * 1000)
  })
})
