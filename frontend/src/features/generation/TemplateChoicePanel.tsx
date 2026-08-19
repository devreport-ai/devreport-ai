/** 생성 중인 보고서에 적용할 HTML 템플릿 갤러리. */
import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { Icon } from '../../components/ui'
import { toDisplayMessage } from '../../lib/api/errors'
import { REPORT_TEMPLATES, type ReportTemplate } from '../report/templates'
import type { useGenerationJob } from './api'

export function TemplateChoicePanel({
  job,
  onRetry,
  initialTemplateId,
  recovered = false,
  projectName = '프로젝트',
  onTemplateChange,
  onConfirm,
  onCancel,
  canceling = false,
  cancelError,
  onComplete,
}: {
  job: ReturnType<typeof useGenerationJob>
  onRetry: () => void
  initialTemplateId?: string
  recovered?: boolean
  projectName?: string
  onTemplateChange?: (templateId: string) => void
  onConfirm?: () => void
  onCancel?: () => void
  canceling?: boolean
  cancelError?: unknown
  onComplete?: () => void
}) {
  const navigate = useNavigate()
  const [templateId, setTemplateId] = useState(() =>
    initialTemplateId && REPORT_TEMPLATES.some((template) => template.id === initialTemplateId)
      ? initialTemplateId
      : REPORT_TEMPLATES[0].id,
  )
  const [category, setCategory] = useState<'전체' | ReportTemplate['category']>('전체')
  const [confirmed, setConfirmed] = useState(recovered)
  const navigatedRef = useRef(false)

  const selected = REPORT_TEMPLATES.find((template) => template.id === templateId)
  const templates = useMemo(
    () =>
      category === '전체'
        ? REPORT_TEMPLATES
        : REPORT_TEMPLATES.filter((template) => template.category === category),
    [category],
  )
  const finished = Boolean(job.data?.status === 'COMPLETED' && job.data.reportId)
  const failed =
    job.error !== null ||
    job.timedOut ||
    job.data?.status === 'FAILED' ||
    job.data?.status === 'CANCELED' ||
    (job.data?.status === 'COMPLETED' && !job.data.reportId)

  useEffect(() => {
    if (!confirmed || failed || !finished || navigatedRef.current) return
    navigatedRef.current = true
    onComplete?.()
    const reportId = job.data?.reportId
    const timer = window.setTimeout(() => {
      if (reportId) void navigate(`/reports/${reportId}`, { state: { templateId } })
    }, 400)
    return () => window.clearTimeout(timer)
  }, [confirmed, failed, finished, job.data?.reportId, navigate, onComplete, templateId])

  const chooseTemplate = (next: string) => {
    setTemplateId(next)
    onTemplateChange?.(next)
  }

  return (
    <main className="template-gallery-main">
      <header className="template-gallery-topbar">
        <div className="app-breadcrumb">
          <span>{projectName}</span>
          <span className="app-breadcrumb__slash">/</span>
          <strong>HTML 템플릿 선택</strong>
        </div>
        <div className="template-gallery-topbar__actions">
          {onCancel && (
            <button
              type="button"
              className="secondary-button"
              onClick={onCancel}
              disabled={canceling}
            >
              <Icon name="x" size={15} />
              생성 취소
            </button>
          )}
          <button
            type="button"
            className="primary-button"
            onClick={() => {
              onConfirm?.()
              setConfirmed(true)
            }}
            disabled={confirmed || failed || selected === undefined || canceling}
          >
            <Icon name="sparkles" size={16} />
            {confirmed ? '생성 중…' : '이 템플릿으로 생성'}
          </button>
        </div>
      </header>

      <div className="template-gallery-content">
        <div className="template-gallery-heading">
          <div>
            <p className="eyebrow">REPORT PRESENTATION</p>
            <h1>보고서 템플릿을 선택하세요</h1>
            <p>
              AI가 작성한 초안을 12개의 실제 HTML 템플릿에 자동 배치합니다. 생성 후 모든 블록을 직접
              편집할 수 있습니다.
            </p>
          </div>
          <div className="template-flow" aria-label="보고서 생성 흐름">
            <FlowStep icon="sparkles" label="AI 초안" />
            <Icon name="chevron-right" size={15} />
            <FlowStep icon="file-text" label="템플릿 적용" active />
            <Icon name="chevron-right" size={15} />
            <FlowStep icon="edit" label="직접 편집" />
          </div>
        </div>

        <div className="template-gallery-filters" role="group" aria-label="템플릿 분류">
          {(['전체', '일반 보고서', '개발 문서', '학술', '에디토리얼', '컴팩트'] as const).map(
            (item) => (
              <button
                key={item}
                type="button"
                aria-pressed={category === item}
                className={
                  category === item ? 'template-filter template-filter--active' : 'template-filter'
                }
                onClick={() => setCategory(item)}
              >
                {item}
              </button>
            ),
          )}
        </div>

        <div className="template-gallery-grid">
          {templates.map((template) => (
            <label
              key={template.id}
              className={
                template.id === templateId
                  ? 'template-card template-card--selected'
                  : 'template-card'
              }
            >
              <input
                type="radio"
                name="report-template"
                value={template.id}
                checked={template.id === templateId}
                disabled={confirmed || failed || canceling}
                onChange={() => chooseTemplate(template.id)}
                className="sr-only"
              />
              <TemplatePreview template={template} />
              <div className="template-card__info">
                <div className="template-card__name-row">
                  <strong>{template.galleryName}</strong>
                  {template.id === templateId && (
                    <span className="template-selected-badge">선택됨</span>
                  )}
                </div>
                <p>{template.description}</p>
                <span className="template-html-badge">
                  <Icon name="file-text" size={13} /> HTML
                </span>
              </div>
            </label>
          ))}
        </div>

        {confirmed && !failed && (
          <div className="template-generation-status" aria-live="polite">
            <div className="template-generation-status__copy">
              <span className="status-badge status-badge--active">
                <span className="status-badge__dot" aria-hidden />
                생성 중
              </span>
              <strong>
                {selected?.galleryName ?? '선택한 템플릿'}으로 보고서를 준비하고 있어요.
              </strong>
            </div>
            <div
              className="template-progress"
              role="progressbar"
              aria-valuenow={job.data?.progress ?? 0}
              aria-valuemin={0}
              aria-valuemax={100}
            >
              <span style={{ width: `${job.data?.progress ?? 0}%` }} />
            </div>
          </div>
        )}

        {failed && (
          <div className="template-generation-error" role="alert">
            <div>
              <strong>보고서 생성에 실패했습니다.</strong>
              {job.data?.failureMessage && <p>{job.data.failureMessage}</p>}
              {job.error !== null && <p>{toDisplayMessage(job.error)}</p>}
            </div>
            <button type="button" className="secondary-button" onClick={onRetry}>
              다시 시도
            </button>
            {cancelError !== undefined && cancelError !== null && (
              <p>{toDisplayMessage(cancelError)}</p>
            )}
          </div>
        )}
      </div>
    </main>
  )
}

function FlowStep({
  icon,
  label,
  active = false,
}: {
  icon: 'sparkles' | 'file-text' | 'edit'
  label: string
  active?: boolean
}) {
  return (
    <span
      className={active ? 'template-flow__step template-flow__step--active' : 'template-flow__step'}
    >
      <Icon name={icon} size={15} />
      {label}
    </span>
  )
}

function TemplatePreview({ template }: { template: ReportTemplate }) {
  return (
    <div
      className={`template-preview template-preview--${template.preview} template-preview--id-${template.id}`}
      aria-hidden
    >
      {template.preview === 'modern' && (
        <>
          <span className="preview-modern__accent" />
          <div className="preview-modern__header">
            <small>DEVREPORT AI</small>
            <strong>프로젝트 분석 보고서</strong>
            <span>작성자 · 소프트웨어공학 · 2026.08</span>
          </div>
          <PreviewSection title="프로젝트 개요" />
          <p>AI 분석을 기반으로 프로젝트 목표와 구현 결과를 구조화했습니다.</p>
          <div className="preview-callout">
            <strong>핵심 요약</strong>
            <span>주요 성과와 기술적 의사결정을 한눈에 확인합니다.</span>
          </div>
        </>
      )}
      {template.preview === 'developer' && (
        <>
          <div className="preview-developer__header">
            <span>docs / project-report.md</span>
            <strong>시스템 구현 보고서</strong>
          </div>
          <small className="preview-code-heading">01. ARCHITECTURE</small>
          <p>서비스 구성과 데이터 흐름을 설명합니다.</p>
          <pre>{`class ReportService {\n  generate(template);\n}`}</pre>
        </>
      )}
      {template.preview === 'academic' && (
        <>
          <div className="preview-academic__header">
            <strong>AI 기반 문서 생성 연구</strong>
            <span>김예찬 · DevReport AI</span>
            <i />
          </div>
          <small>초록</small>
          <p>본 연구는 구조화된 자료를 기반으로 보고서를 자동 생성하고 편집하는 방법을 제안한다.</p>
          <strong>1. 서론</strong>
          <p>문서 템플릿과 콘텐츠 모델의 분리를 통해 일관된 결과물을 제공합니다.</p>
        </>
      )}
      {template.preview === 'editorial' && (
        <>
          <div className="preview-editorial__header">
            <small>PROJECT · 2026</small>
            <strong>
              아이디어에서
              <br />
              결과까지
            </strong>
            <span>
              PROJECT
              <br />
              IMAGE
            </span>
          </div>
          <div className="preview-editorial__columns">
            <strong>프로젝트 이야기</strong>
            <p>문제 발견부터 해결 과정까지 핵심 이야기를 전달합니다.</p>
            <i />
            <i />
            <i />
          </div>
        </>
      )}
      {template.preview === 'side-panel' && (
        <div className="preview-side-panel">
          <aside>
            <b>●</b>
            <strong>
              프로젝트
              <br />
              보고서
            </strong>
            <small>
              2026.08
              <br />
              DEVREPORT AI
            </small>
          </aside>
          <div>
            <h4>핵심 성과</h4>
            <p>
              <b>01</b> 요구사항 분석
            </p>
            <p>
              <b>02</b> 시스템 구현
            </p>
            <p>
              <b>03</b> 결과 검증
            </p>
          </div>
        </div>
      )}
      {template.preview === 'minimal' && (
        <>
          <small className="preview-minimal__number">REPORT / 001</small>
          <strong className="preview-minimal__title">
            프로젝트
            <br />
            결과 보고서
          </strong>
          <i className="preview-minimal__rule" />
          <div className="preview-minimal__columns">
            <span>
              <b>개요</b>목표와 범위를 간결하게 정리합니다.
            </span>
            <span>
              <b>결과</b>핵심 구현 결과와 배운 점을 기록합니다.
            </span>
          </div>
        </>
      )}
    </div>
  )
}

function PreviewSection({ title }: { title: string }) {
  return (
    <div className="preview-section">
      <i />
      <strong>{title}</strong>
    </div>
  )
}
