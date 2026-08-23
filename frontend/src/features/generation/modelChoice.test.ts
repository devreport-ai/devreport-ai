import { describe, expect, it } from 'vitest'
import type { AiModelOption } from '../../lib/contracts/types'
import { choiceKey, resolveChoice } from './modelChoice'

const flashLite: AiModelOption = {
  provider: 'GEMINI',
  model: 'gemini-3.5-flash-lite',
  label: 'Flash-Lite',
  serverDefault: true,
  available: true,
}
const pro: AiModelOption = {
  provider: 'GEMINI',
  model: 'gemini-3.5-pro',
  label: 'Pro',
  serverDefault: false,
  available: false,
}

describe('resolveChoice', () => {
  it('목록이 비어 있으면 null 을 돌려준다', () => {
    expect(resolveChoice([], choiceKey(pro))).toBeNull()
  })

  it('선택이 없으면 서버 기본 모델을 고른다', () => {
    expect(resolveChoice([pro, flashLite], null)).toBe(flashLite)
  })

  it('키 등록으로 사용 가능해진 모델은 선택을 유지한다', () => {
    const unlocked = { ...pro, available: true }
    expect(resolveChoice([flashLite, unlocked], choiceKey(pro))).toBe(unlocked)
  })

  it('키를 지워 쓸 수 없게 된 모델은 기본 모델로 되돌린다', () => {
    expect(resolveChoice([flashLite, pro], choiceKey(pro))).toBe(flashLite)
  })

  it('allowlist 에서 사라진 모델도 기본 모델로 되돌린다', () => {
    expect(resolveChoice([flashLite], 'GEMINI/gemini-9')).toBe(flashLite)
  })
})
