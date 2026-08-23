/**
 * API Key 관리 — AI provider API Key 등록·교체·삭제 (#116).
 *
 * 키 원문은 입력란에서 서버로 가는 순간에만 존재한다. 저장이 끝나면 입력란을 비우고,
 * 서버가 돌려주는 마스킹 힌트만 보여준다. 원문 재조회 API 는 없다.
 */
import { useState } from 'react'
import { Link } from 'react-router'
import { AppNav } from '../components/AppNav'
import { AppTopBar } from '../components/ui'
import {
  useAiCredentials,
  useAiModels,
  useDeleteAiCredential,
  useSaveAiCredential,
} from '../features/settings/api'
import { toDisplayMessage } from '../lib/api/errors'
import {
  AI_PROVIDER_LABELS,
  type AiCredentialResponse,
  type AiModelOption,
  type AiProvider,
} from '../lib/contracts/types'

export default function ApiKeysPage() {
  const credentials = useAiCredentials()
  const models = useAiModels()

  const providers = providersFrom(models.data?.items ?? [])
  const byProvider = new Map(
    (credentials.data?.items ?? []).map((item) => [item.provider, item] as const),
  )

  return (
    <div className="app-page">
      <AppNav screen="API 키" />
      <AppTopBar root="API 키" current="API Key 관리">
        <Link to="/" className="topbar-link">
          프로젝트 목록
        </Link>
      </AppTopBar>
      <main className="app-content">
        <header className="project-detail-header">
          <div className="project-detail-heading">
            <div>
              <p className="eyebrow">API KEYS</p>
              <h1>API Key 관리</h1>
              <p>
                본인의 LLM API Key 를 등록하면 더 많은 모델을 선택해 보고서를 생성할 수 있습니다.
              </p>
            </div>
          </div>
        </header>

        <div className="detail-stack">
          <section className="surface-card detail-card">
            <div className="detail-card__header">
              <div>
                <h2>API Key 처리 안내</h2>
              </div>
            </div>
            <ul className="settings-notes">
              <li>
                키는 서버에서 암호화해 보관하며, 보고서 생성을 실행하는 순간에만 복호화합니다.
              </li>
              <li>등록한 키 원문은 다시 조회할 수 없고, 화면에는 끝 4자리만 표시됩니다.</li>
              <li>
                등록한 키로 생성하면 <strong>비용은 해당 provider 계정에 청구</strong>됩니다. 키를
                삭제하면 즉시 서비스 기본 모델만 사용할 수 있습니다.
              </li>
              <li>키를 등록하지 않아도 서비스 기본 모델로 보고서를 만들 수 있습니다.</li>
            </ul>
          </section>

          {(credentials.isPending || models.isPending) && (
            <p className="inline-hint">설정을 불러오는 중…</p>
          )}
          {credentials.error && (
            <p role="alert" className="inline-alert">
              {toDisplayMessage(credentials.error)}
            </p>
          )}
          {models.error && (
            <p role="alert" className="inline-alert">
              {toDisplayMessage(models.error)}
            </p>
          )}

          {providers.map((provider) => (
            <ProviderCard
              key={provider}
              provider={provider}
              credential={byProvider.get(provider) ?? null}
              models={(models.data?.items ?? []).filter((item) => item.provider === provider)}
            />
          ))}
        </div>
      </main>
    </div>
  )
}

/** allowlist 에 등장하는 provider 만 보여준다. 목록에 없는 provider 는 키를 등록해도 쓸 곳이 없다. */
function providersFrom(options: AiModelOption[]): AiProvider[] {
  const seen = new Set<AiProvider>()
  for (const option of options) seen.add(option.provider)
  return [...seen]
}

function ProviderCard({
  provider,
  credential,
  models,
}: {
  provider: AiProvider
  credential: AiCredentialResponse | null
  models: AiModelOption[]
}) {
  const [apiKey, setApiKey] = useState('')
  // 오류 문구는 별도 상태로 들고 있는다. mutation 의 error 상태를 그대로 쓰면 실패한 mutation 의
  // variables(키 원문)가 카드가 마운트된 동안 React Query 에 남기 때문이다.
  const [saveError, setSaveError] = useState<string | null>(null)
  const save = useSaveAiCredential()
  const remove = useDeleteAiCredential()
  const trimmed = apiKey.trim()

  const handleSave = (event: React.FormEvent) => {
    event.preventDefault()
    setSaveError(null)
    save.mutate(
      { provider, apiKey: trimmed },
      {
        onSuccess: () => setApiKey(''),
        onError: (error) => setSaveError(toDisplayMessage(error)),
        // 성공·실패 모두 mutation 상태(variables 의 키 원문)를 즉시 비운다.
        onSettled: () => save.reset(),
      },
    )
  }

  const label = AI_PROVIDER_LABELS[provider]
  const modelNames = models.map((model) => model.label).join(' · ')

  return (
    <section className="surface-card detail-card" aria-labelledby={`provider-${provider}`}>
      <div className="detail-card__header">
        <div>
          <h2 id={`provider-${provider}`}>{label}</h2>
          <p className="inline-hint">사용 가능한 모델: {modelNames || '—'}</p>
        </div>
        {credential ? (
          <span className="status-badge status-badge--active">
            <span className="status-badge__dot" aria-hidden />
            등록됨 {credential.keyHint}
          </span>
        ) : (
          <span className="status-badge">미등록</span>
        )}
      </div>

      <form onSubmit={handleSave} className="generation-form">
        <div className="field-group">
          <label htmlFor={`api-key-${provider}`} className="field-label">
            {credential ? '새 API Key 로 교체' : 'API Key'}
          </label>
          <input
            id={`api-key-${provider}`}
            type="password"
            autoComplete="off"
            spellCheck={false}
            value={apiKey}
            onChange={(event) => setApiKey(event.target.value)}
            placeholder={`${label} API Key 를 붙여넣으세요`}
            className="field-control"
          />
        </div>
        <div className="settings-actions">
          <button
            type="submit"
            className="primary-button"
            disabled={trimmed.length < 8 || save.isPending}
          >
            {save.isPending ? '확인 중…' : credential ? '교체' : '등록'}
          </button>
          {credential && (
            <button
              type="button"
              className="danger-button"
              disabled={remove.isPending}
              onClick={() => remove.mutate(provider)}
            >
              {remove.isPending ? '삭제 중…' : '삭제'}
            </button>
          )}
        </div>
        {saveError && (
          <p role="alert" className="inline-alert">
            {saveError}
          </p>
        )}
        {remove.error && (
          <p role="alert" className="inline-alert">
            {toDisplayMessage(remove.error)}
          </p>
        )}
      </form>
    </section>
  )
}
