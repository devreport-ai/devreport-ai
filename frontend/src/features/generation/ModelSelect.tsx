/**
 * 생성 폼의 provider·모델 선택 — allowlist 와 계정의 키 등록 여부를 반영한다.
 *
 * 키가 없어 고를 수 없는 모델은 비활성으로 보여주고 설정 화면으로 안내한다. 숨기지 않는
 * 이유는 "키를 등록하면 더 좋은 모델을 쓸 수 있다"는 사실 자체를 알려야 하기 때문이다.
 */
import { Link } from 'react-router'
import { AI_PROVIDER_LABELS, type AiModelOption } from '../../lib/contracts/types'
import { choiceKey } from './modelChoice'

export function ModelSelect({
  options,
  value,
  onChange,
  disabled,
}: {
  options: AiModelOption[]
  value: AiModelOption | null
  onChange: (option: AiModelOption) => void
  disabled?: boolean
}) {
  const selectedKey = value ? choiceKey(value) : ''
  const lockedCount = options.filter((option) => !option.available).length

  return (
    <div className="field-group">
      <label htmlFor="generation-model" className="field-label">
        AI 모델
      </label>
      <select
        id="generation-model"
        className="field-control"
        value={selectedKey}
        disabled={disabled || options.length === 0}
        onChange={(event) => {
          const next = options.find((option) => choiceKey(option) === event.target.value)
          if (next && next.available) onChange(next)
        }}
      >
        {options.length === 0 && <option value="">모델 목록을 불러오는 중…</option>}
        {options.map((option) => (
          <option key={choiceKey(option)} value={choiceKey(option)} disabled={!option.available}>
            {AI_PROVIDER_LABELS[option.provider]} · {option.label}
            {option.serverDefault ? ' (기본)' : ''}
            {option.available ? '' : ' — API Key 등록 필요'}
          </option>
        ))}
      </select>
      <p className="inline-hint">
        {value?.usesUserKey
          ? '등록한 API Key 로 실행되며 비용은 해당 provider 계정에 청구됩니다.'
          : '서비스 키로 실행됩니다.'}
        {lockedCount > 0 && (
          <>
            {' '}
            다른 모델을 쓰려면{' '}
            <Link to="/api-keys" className="underline">
              API 키 추가에서 등록
            </Link>
            하세요.
          </>
        )}
      </p>
    </div>
  )
}
