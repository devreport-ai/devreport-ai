/**
 * 생성 요청 직후 보여주는 템플릿 선택 (#36).
 *
 * 생성은 백그라운드에서 이미 돌고 있지만 여기서는 티를 내지 않는다 — 고르는 행위가
 * 대기 시간을 대신한다. 진행·실패는 사용자가 선택을 마친 시점에만 드러낸다.
 */
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import { REPORT_TEMPLATES } from '../report/templates'
import { toDisplayMessage } from '../../lib/api/errors'
import type { useGenerationJob } from './api'

export function TemplateChoicePanel({
  job,
  onRetry,
}: {
  job: ReturnType<typeof useGenerationJob>
  onRetry: () => void
}) {
  const navigate = useNavigate()
  const [templateId, setTemplateId] = useState(REPORT_TEMPLATES[0].id)
  // 사용자가 "시작"을 눌렀는데 생성이 아직인 상태. 이때부터만 기다림을 보여준다.
  const [confirmed, setConfirmed] = useState(false)

  const finished = job.data?.status === 'COMPLETED' && job.data.reportId
  const failed =
    job.error !== null ||
    job.timedOut ||
    job.data?.status === 'FAILED' ||
    job.data?.status === 'CANCELED' ||
    (job.data?.status === 'COMPLETED' && !job.data.reportId)

  useEffect(() => {
    if (confirmed && finished && job.data?.reportId) {
      void navigate(`/reports/${job.data.reportId}`, { state: { templateId } })
    }
  }, [confirmed, finished, job.data?.reportId, navigate, templateId])

  // 선택을 마쳤을 때만 실패를 드러낸다
  if (confirmed && failed) {
    return (
      <section role="alert" className="space-y-2">
        <p className="font-medium text-red-600">보고서 생성에 실패했습니다.</p>
        {job.data?.failureMessage && (
          <p className="text-sm text-red-600">{job.data.failureMessage}</p>
        )}
        {job.error !== null && (
          <p className="text-sm text-red-600">{toDisplayMessage(job.error)}</p>
        )}
        <button
          type="button"
          onClick={onRetry}
          className="rounded border border-gray-300 px-3 py-1 text-sm hover:bg-gray-50"
        >
          다시 시도
        </button>
      </section>
    )
  }

  if (confirmed) {
    return (
      <p aria-live="polite" className="text-gray-700">
        보고서를 마무리하는 중…
      </p>
    )
  }

  return (
    <section className="space-y-4">
      <h2 className="text-lg font-semibold">어떤 디자인으로 만들까요?</h2>
      <p className="text-sm text-gray-600">나중에 편집 화면에서 언제든 바꿀 수 있습니다.</p>

      <div className="flex gap-4">
        {REPORT_TEMPLATES.map((t) => (
          <label
            key={t.id}
            className={`flex-1 cursor-pointer rounded border p-4 ${
              templateId === t.id ? 'border-gray-900 ring-1 ring-gray-900' : 'border-gray-300'
            }`}
          >
            <input
              type="radio"
              name="template"
              value={t.id}
              checked={templateId === t.id}
              onChange={() => setTemplateId(t.id)}
              className="sr-only"
            />
            <TemplateThumbnail id={t.id} />
            <span className="mt-2 block text-center text-sm font-medium">{t.name}</span>
          </label>
        ))}
      </div>

      <button
        type="button"
        onClick={() => setConfirmed(true)}
        className="rounded bg-gray-900 px-4 py-2 text-white"
      >
        이 디자인으로 시작
      </button>
    </section>
  )
}

/** 실제 디자인 확정 전까지의 간단한 도식 썸네일. */
function TemplateThumbnail({ id }: { id: string }) {
  const dense = id === 'compact'
  return (
    <div aria-hidden className="mx-auto h-28 w-20 rounded border border-gray-200 bg-white p-1.5">
      <div className={`mb-1 bg-gray-800 ${dense ? 'h-1.5 w-10' : 'h-2 w-14'}`} />
      <div className="mb-1.5 h-1 w-8 bg-gray-300" />
      {Array.from({ length: dense ? 8 : 5 }).map((_, i) => (
        <div key={i} className={`mb-1 bg-gray-200 ${dense ? 'h-0.5' : 'h-1'}`} />
      ))}
    </div>
  )
}
