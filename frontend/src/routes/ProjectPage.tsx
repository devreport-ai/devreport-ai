/**
 * 프로젝트 화면 — 업로드부터 생성 요청·진행 상태까지 한 페이지에서 처리한다.
 *
 * 생성 진행을 별도 페이지로 빼지 않은 이유: 사용자가 파일 목록을 보면서 기다리는 게
 * 자연스럽고, 실패했을 때 같은 화면에서 바로 다시 요청할 수 있어야 하기 때문이다.
 *
 * 생성을 요청하면 곧바로 템플릿 선택으로 넘어간다(#36). 생성은 뒤에서 돌지만
 * 티를 내지 않는다 — 고르는 행위가 대기 시간을 대신한다.
 */
import { useMemo, useState } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router'
import { AppNav } from '../components/AppNav'
import { AppTopBar, Icon } from '../components/ui'
import { UploadPanel } from '../features/files/UploadPanel'
import { useDeleteFile, useProjectFiles } from '../features/files/api'
import {
  loadGenerationRecovery,
  saveGenerationRecovery,
  useStartGeneration,
} from '../features/generation/api'
import {
  choiceKey,
  loadModelChoice,
  resolveChoice,
  saveModelChoice,
} from '../features/generation/modelChoice'
import { ModelSelect } from '../features/generation/ModelSelect'
import { useAiModels } from '../features/settings/api'
import { findTemplate } from '../features/report/templates'
import { useProject, useProjectReports } from '../features/projects/api'
import { toDisplayMessage } from '../lib/api/errors'
import { aiInputExclusionReason, isAiInputFile, type FileResponse } from '../lib/contracts/types'

export default function ProjectPage() {
  const { projectId = '' } = useParams()
  return <ProjectPageContent key={projectId} projectId={projectId} />
}

function resolveFirstInvalidId(
  selectable: FileResponse[],
  selectedIds: string[],
  title: string,
  instructions: string,
  modelReady = true,
): string {
  if (selectedIds.length === 0) {
    return selectable[0] ? `file-${selectable[0].id}` : 'file-selection'
  }
  if (title.trim() === '') return 'title'
  if (!modelReady) return 'generation-model'
  if (instructions.trim() === '') return 'instructions'
  return 'policy-agreement'
}

function ProjectPageContent({ projectId }: { projectId: string }) {
  const navigate = useNavigate()
  const project = useProject(projectId)
  const files = useProjectFiles(projectId)
  const [reportPage, setReportPage] = useState(0)
  const reports = useProjectReports(projectId, reportPage)
  const deleteFile = useDeleteFile(projectId)
  const startGeneration = useStartGeneration(projectId)

  const [selectedIds, setSelectedIds] = useState<string[]>([])
  const [title, setTitle] = useState('')
  const [author, setAuthor] = useState('')
  const [course, setCourse] = useState('')
  const [date, setDate] = useState(todayLocal)
  const [instructions, setInstructions] = useState('')
  const [policyAgreed, setPolicyAgreed] = useState(false)
  const [showValidation, setShowValidation] = useState(false)
  const recovery = loadGenerationRecovery(projectId)

  // 모델 목록은 계정의 키 등록 여부에 따라 달라진다. 선택은 키 문자열로만 들고 있고
  // 실제 옵션은 목록에서 매번 다시 찾는다 — 목록이 바뀌어 못 쓰게 된 선택은 기본 모델로 돌아간다.
  const models = useAiModels()
  const modelOptions = useMemo(() => models.data?.items ?? [], [models.data])
  const [selectedModelKey, setSelectedModelKey] = useState<string | null>(() => {
    const remembered = loadModelChoice()
    return remembered ? choiceKey(remembered) : null
  })
  const modelChoice = useMemo(
    () => resolveChoice(modelOptions, selectedModelKey),
    [modelOptions, selectedModelKey],
  )

  const items = files.data?.items ?? []
  const selectable = items.filter(isAiInputFile)

  const toggle = (fileId: string) => {
    setSelectedIds((prev) =>
      prev.includes(fileId) ? prev.filter((id) => id !== fileId) : [...prev, fileId],
    )
  }

  const handleGenerate = (event: React.FormEvent) => {
    event.preventDefault()
    if (!canSubmit || !policyAgreed) {
      setShowValidation(true)
      const firstInvalidId = resolveFirstInvalidId(
        selectable,
        selectedIds,
        title,
        instructions,
        modelChoice !== null,
      )
      const firstInvalid = document.getElementById(firstInvalidId)
      firstInvalid?.focus({ preventScroll: true })
      firstInvalid?.scrollIntoView?.({ behavior: 'smooth', block: 'center' })
      return
    }
    startGeneration.mutate(
      {
        fileIds: selectedIds,
        metadata: {
          title: title.trim(),
          // 빈 문자열을 보내면 표지에 빈 줄이 생긴다. 값이 있을 때만 키를 넣는다.
          ...(author.trim() && { author: author.trim() }),
          ...(course.trim() && { course: course.trim() }),
          ...(date && { date }),
        },
        instructions: instructions.trim(),
        // 서버 기본 모델도 명시해서 보낸다. 나중에 기본값이 바뀌어도 사용자가 본 모델 그대로 실행된다.
        ...(modelChoice && { provider: modelChoice.provider, model: modelChoice.model }),
      },
      {
        onSuccess: ({ jobId: id }) => {
          const next = { jobId: id, templateId: 'default', confirmed: false }
          saveGenerationRecovery(projectId, next)
          void navigate(`/projects/${projectId}/templates`)
        },
      },
    )
  }

  // timedOut 을 빼면 상한에 걸린 뒤에도 running 이 true 로 굳어 버튼이 영구 비활성이 된다.
  // 계약상 필수는 fileIds(1개 이상)·metadata.title·instructions 세 가지다.
  // 모델 목록이 아직 없으면 기억해 둔 선택이 조용히 기본 모델로 바뀌므로, 목록이 올 때까지 제출을 막는다.
  const canSubmit =
    selectedIds.length > 0 &&
    title.trim() !== '' &&
    instructions.trim() !== '' &&
    modelChoice !== null

  if (recovery !== null) {
    return <Navigate to={`/projects/${projectId}/templates`} replace />
  }

  return (
    <div className="app-page">
      <AppNav screen={project.data?.name ?? '보고서 만들기'} />
      <AppTopBar root="프로젝트" current={project.data?.name ?? '프로젝트'}>
        <Link to="/" className="topbar-link">
          프로젝트 목록
        </Link>
      </AppTopBar>
      <main className="app-content">
        <header className="project-detail-header">
          <div className="project-detail-heading">
            <div className="project-detail-icon" aria-hidden>
              <Icon name="folder" size={21} />
            </div>
            <div>
              <p className="eyebrow">PROJECT WORKSPACE</p>
              {project.data && <h1>{project.data.name}</h1>}
              {project.data && <p>프로젝트 자료를 관리하고 보고서를 다시 열 수 있습니다.</p>}
            </div>
          </div>
          <span className="status-badge status-badge--active">
            <span className="status-badge__dot" aria-hidden />
            진행 중
          </span>
        </header>

        {project.isPending && <p className="inline-hint">프로젝트를 불러오는 중…</p>}
        {project.error && (
          <p role="alert" className="inline-alert">
            {toDisplayMessage(project.error)}
          </p>
        )}

        <div className="detail-stack">
          <ReportList page={reportPage} reports={reports} onPageChange={setReportPage} />

          <section className="surface-card detail-card detail-card--upload">
            <UploadPanel projectId={projectId} />
            <p className="inline-hint">
              업로드 자료는 보고서 생성 과정에서 AI 제공자에게 전달될 수 있습니다.{' '}
              <Link to="/policies#ai-data" className="underline">
                자료 처리 안내
              </Link>
            </p>
          </section>

          <section
            id="file-selection"
            className="surface-card detail-card detail-card--files"
            tabIndex={-1}
            aria-describedby={
              showValidation && selectedIds.length === 0 ? 'file-selection-error' : undefined
            }
          >
            <div className="detail-card__header">
              <div>
                <h2>분석할 파일 선택</h2>
                <p className="inline-hint">보고서 생성에 사용할 자료를 선택하세요.</p>
              </div>
            </div>

            {files.isPending && <p className="inline-hint">불러오는 중…</p>}
            {files.error && (
              <p role="alert" className="inline-alert">
                {toDisplayMessage(files.error)}
              </p>
            )}
            {files.data && items.length === 0 && (
              <p className="inline-hint">아직 올린 파일이 없습니다.</p>
            )}

            {items.length > 0 && (
              <ul className="file-list">
                {items.map((file) => (
                  <FileRow
                    key={file.id}
                    file={file}
                    checked={selectedIds.includes(file.id)}
                    invalid={showValidation && selectedIds.length === 0}
                    onToggle={() => toggle(file.id)}
                    onDelete={() =>
                      deleteFile.mutate(file.id, {
                        // 지운 파일이 선택 목록에 남으면 없는 fileId 로 생성 요청이 나간다.
                        onSuccess: () =>
                          setSelectedIds((prev) => prev.filter((id) => id !== file.id)),
                      })
                    }
                  />
                ))}
              </ul>
            )}

            {deleteFile.error && (
              <p role="alert" className="inline-alert">
                {toDisplayMessage(deleteFile.error)}
              </p>
            )}

            {items.length > 0 && selectable.length === 0 && (
              <p className="inline-hint">
                AI가 분석할 수 있는 파일이 없습니다. ZIP · PDF/DOCX · MD/TXT · 소스 코드 · PNG/JPG를
                올려 주세요.
              </p>
            )}

            {showValidation && selectedIds.length === 0 && (
              <p id="file-selection-error" role="alert" className="inline-alert">
                분석할 파일을 1개 이상 선택해 주세요.
              </p>
            )}
          </section>

          <section className="surface-card detail-card detail-card--information">
            <form onSubmit={handleGenerate} className="generation-form">
              <div className="detail-card__header">
                <div>
                  <h2>보고서 정보</h2>
                  <p className="inline-hint">보고서 표지와 생성 방향을 설정합니다.</p>
                </div>
              </div>

              <Field
                id="title"
                label="제목"
                required
                value={title}
                onChange={setTitle}
                error={showValidation && title.trim() === '' ? '제목을 입력해 주세요.' : undefined}
              />
              <Field id="author" label="작성자" value={author} onChange={setAuthor} />
              <Field id="course" label="과목" value={course} onChange={setCourse} />
              <Field id="date" label="날짜" type="date" value={date} onChange={setDate} />

              <ModelSelect
                options={modelOptions}
                value={modelChoice}
                onChange={(option) => {
                  setSelectedModelKey(choiceKey(option))
                  saveModelChoice({ provider: option.provider, model: option.model })
                }}
                disabled={startGeneration.isPending}
                error={
                  showValidation && modelChoice === null
                    ? 'AI 모델 목록을 불러오는 중입니다. 잠시 후 다시 시도해 주세요.'
                    : undefined
                }
              />
              {models.error && (
                <p role="alert" className="inline-alert">
                  {toDisplayMessage(models.error)}
                </p>
              )}

              <div className="field-group">
                <label htmlFor="instructions" className="field-label">
                  작성 지시사항 <span className="text-red-600">*</span>
                </label>
                <textarea
                  id="instructions"
                  value={instructions}
                  onChange={(e) => setInstructions(e.target.value)}
                  aria-invalid={showValidation && instructions.trim() === ''}
                  aria-describedby={
                    showValidation && instructions.trim() === '' ? 'instructions-error' : undefined
                  }
                  rows={4}
                  placeholder="어떤 내용을 강조할지, 어떤 형식으로 쓸지 적어 주세요."
                  className="field-control"
                />
                {showValidation && instructions.trim() === '' && (
                  <p id="instructions-error" role="alert" className="inline-alert">
                    작성 지시사항을 입력해 주세요.
                  </p>
                )}
              </div>

              <label className="generation-form__agreement">
                <input
                  id="policy-agreement"
                  type="checkbox"
                  checked={policyAgreed}
                  onChange={(event) => setPolicyAgreed(event.target.checked)}
                  aria-invalid={showValidation && !policyAgreed}
                  aria-describedby={
                    showValidation && !policyAgreed ? 'policy-agreement-error' : undefined
                  }
                  className="mt-0.5"
                />
                <span>
                  자료가 AI 제공자에게 전달될 수 있음을 확인했습니다.{' '}
                  <Link to="/policies#ai-data" className="underline">
                    자세히 보기
                  </Link>
                </span>
              </label>

              {showValidation && !policyAgreed && (
                <p id="policy-agreement-error" role="alert" className="inline-alert">
                  자료 전달 안내를 확인해 주세요.
                </p>
              )}

              <button type="submit" disabled={startGeneration.isPending} className="primary-button">
                {startGeneration.isPending ? '요청 중…' : '보고서 생성'}
              </button>

              {startGeneration.error && (
                <p role="alert" className="inline-alert">
                  {toDisplayMessage(startGeneration.error)}
                </p>
              )}
            </form>
          </section>
        </div>
      </main>
    </div>
  )
}

function ReportList({
  page,
  reports,
  onPageChange,
}: {
  page: number
  reports: ReturnType<typeof useProjectReports>
  onPageChange: (page: number) => void
}) {
  const data = reports.data

  return (
    <section aria-labelledby="report-list-heading" className="surface-card detail-card">
      <div className="detail-card__header">
        <div>
          <h2 id="report-list-heading">기존 보고서</h2>
          <p className="inline-hint">최근 작업한 보고서를 다시 열 수 있습니다.</p>
        </div>
      </div>

      {reports.isPending && <p className="inline-hint">보고서를 불러오는 중…</p>}
      {reports.error && (
        <p role="alert" className="inline-alert">
          {toDisplayMessage(reports.error)}
        </p>
      )}
      {data && data.items.length === 0 && (
        <p className="inline-hint">
          {data.totalElements === 0
            ? '아직 생성한 보고서가 없습니다.'
            : '이 페이지에 보고서가 없습니다.'}
        </p>
      )}

      {data && data.items.length > 0 && (
        <ul className="report-list">
          {data.items.map((report) => (
            <li key={report.id} className="report-row">
              <span className="file-row__icon" aria-hidden>
                R
              </span>
              <Link to={`/reports/${report.id}`} className="report-row__main">
                <span className="report-row__title">보고서 {report.id.slice(0, 8)}</span>
                <span className="report-row__meta">
                  <span>{findTemplate(report.templateId)?.name ?? '템플릿 미선택'}</span>
                  <span>버전 {report.version}</span>
                  <time dateTime={report.updatedAt}>{formatUpdatedAt(report.updatedAt)}</time>
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}

      {data && data.totalPages > 1 && (
        <nav aria-label="보고서 페이지 이동" className="pagination">
          <button
            type="button"
            onClick={() => onPageChange(page - 1)}
            disabled={page === 0 || reports.isFetching}
          >
            이전 페이지
          </button>
          <span className="text-sm text-gray-600">
            {page + 1} / {data.totalPages}
          </span>
          <button
            type="button"
            onClick={() => onPageChange(page + 1)}
            disabled={page + 1 >= data.totalPages || reports.isFetching}
          >
            다음 페이지
          </button>
        </nav>
      )}
    </section>
  )
}

function formatUpdatedAt(value: string): string {
  return new Date(value).toLocaleString('ko-KR')
}

/**
 * 오늘 날짜를 YYYY-MM-DD 로 만든다.
 *
 * `toISOString()` 은 UTC 기준이라 한국 시간 오전 9시 이전에는 하루 전 날짜가 나온다.
 * 사용자가 보는 달력과 어긋나므로 로컬 시간대로 직접 만든다.
 */
function todayLocal(): string {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

function Field({
  id,
  label,
  value,
  onChange,
  required,
  type = 'text',
  error,
}: {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  required?: boolean
  type?: 'text' | 'date'
  error?: string
}) {
  return (
    <div className="field-group">
      <label htmlFor={id} className="field-label">
        {label} {required && <span className="text-red-600">*</span>}
      </label>
      <input
        id={id}
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-invalid={error !== undefined}
        aria-describedby={error ? `${id}-error` : undefined}
        className="field-control"
      />
      {error && (
        <p id={`${id}-error`} role="alert" className="inline-alert">
          {error}
        </p>
      )}
    </div>
  )
}

function FileRow({
  file,
  checked,
  invalid,
  onToggle,
  onDelete,
}: {
  file: FileResponse
  checked: boolean
  invalid: boolean
  onToggle: () => void
  onDelete: () => void
}) {
  const usable = isAiInputFile(file)
  const exclusionReason = aiInputExclusionReason(file)

  return (
    <li className="file-row">
      <input
        type="checkbox"
        id={`file-${file.id}`}
        checked={checked}
        onChange={onToggle}
        disabled={!usable}
        aria-invalid={invalid && usable}
        aria-describedby={invalid && usable ? 'file-selection-error' : undefined}
      />
      <span className="file-row__icon" aria-hidden>
        <Icon name="file-text" size={15} />
      </span>
      <label htmlFor={`file-${file.id}`} className="file-row__main">
        <span className="file-row__name">{file.originalName}</span>
        <span className="file-row__meta">
          {file.contentType}
          <span>{exclusionReason ?? 'AI 분석 대상'}</span>
        </span>
      </label>
      <button
        type="button"
        onClick={onDelete}
        className="file-row__delete"
        aria-label={`${file.originalName} 삭제`}
      >
        <Icon name="trash" size={15} />
      </button>
    </li>
  )
}
