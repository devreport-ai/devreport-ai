/** 내보내기 폴링 판단 — 생성 폴링과 같은 규칙(종료·오류·상한 중단)을 고정한다. */
import { describe, expect, it } from 'vitest'
import { isExportFinished, nextExportPollInterval } from './exportApi'

describe('nextExportPollInterval', () => {
  it('진행 중이면 계속 폴링한다', () => {
    expect(nextExportPollInterval({ error: null, status: 'PENDING', dataUpdateCount: 1 })).toBe(
      2000,
    )
    expect(nextExportPollInterval({ error: null, status: 'PROCESSING', dataUpdateCount: 2 })).toBe(
      3000,
    )
  })

  it('종료 상태에서 멈춘다', () => {
    for (const status of ['COMPLETED', 'FAILED', 'EXPIRED'] as const) {
      expect(isExportFinished(status)).toBe(true)
      expect(nextExportPollInterval({ error: null, status, dataUpdateCount: 1 })).toBe(false)
    }
  })

  it('오류·상한에서 멈춘다', () => {
    expect(
      nextExportPollInterval({ error: new Error('x'), status: 'PENDING', dataUpdateCount: 1 }),
    ).toBe(false)
    expect(nextExportPollInterval({ error: null, status: 'PENDING', dataUpdateCount: 60 })).toBe(
      false,
    )
  })
})
