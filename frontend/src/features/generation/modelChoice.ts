/**
 * 생성에 쓸 provider·모델 선택.
 *
 * 서버가 주는 allowlist(`GET /api/ai/models`) 안에서만 고른다. 마지막 선택은 localStorage 에
 * 모델 ID 만 기억한다 — API Key 는 여기 저장하지 않는다(#116 보안 원칙).
 */
import type { AiModelOption, AiProvider } from '../../lib/contracts/types'

export interface ModelChoice {
  provider: AiProvider
  model: string
}

const STORAGE_KEY = 'devreport:generation:model'

export function loadModelChoice(): ModelChoice | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const value: unknown = JSON.parse(raw)
    if (
      typeof value !== 'object' ||
      value === null ||
      typeof (value as ModelChoice).provider !== 'string' ||
      typeof (value as ModelChoice).model !== 'string'
    ) {
      return null
    }
    return { provider: (value as ModelChoice).provider, model: (value as ModelChoice).model }
  } catch {
    return null
  }
}

export function saveModelChoice(choice: ModelChoice): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(choice))
  } catch {
    // 저장소가 막힌 브라우저에서도 생성은 계속한다.
  }
}

/** 옵션 목록을 `provider/model` 한 문자열 키로 다룬다. select 의 value 로 쓰기 위함. */
export function choiceKey(choice: { provider: AiProvider; model: string }): string {
  return `${choice.provider}/${choice.model}`
}

/**
 * 실제로 보낼 선택을 고른다.
 *
 * 선택(`provider/model` 키)이 목록에 없거나(allowlist 변경) 더 이상 쓸 수 없으면(키 삭제)
 * 서버 기본 모델로 조용히 되돌린다. 기본 모델이 목록에 없는 일은 계약상 없지만, 없으면 첫 항목을 쓴다.
 */
export function resolveChoice(
  options: AiModelOption[],
  selectedKey: string | null,
): AiModelOption | null {
  if (options.length === 0) return null
  if (selectedKey) {
    const match = options.find((option) => choiceKey(option) === selectedKey && option.available)
    if (match) return match
  }
  return options.find((option) => option.serverDefault) ?? options[0]
}
