/**
 * 프로젝트 화면 — 업로드부터 생성 요청·진행 상태까지 한 페이지에서 처리한다.
 *
 * 생성 진행을 별도 페이지로 빼지 않은 이유: 사용자가 파일 목록을 보면서 기다리는 게
 * 자연스럽고, 실패했을 때 같은 화면에서 바로 다시 요청할 수 있어야 하기 때문이다.
 *
 * 템플릿 선택은 이번 범위가 아니다. 템플릿 저장은 편집기의 자동 저장·버전 관리와
 * 한 몸이라 #38 에서 함께 다룬다.
 */
import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { UploadPanel } from '../features/files/UploadPanel'
import { useDeleteFile, useProjectFiles } from '../features/files/api'
import { useGenerationJob, useStartGeneration } from '../features/generation/api'
import { toDisplayMessage } from '../lib/api/errors'
import { isAiInputFile, toGenerationRequest, type FileResponse } from '../lib/contracts/types'

export default function ProjectPage() {
  const { projectId = '' } = useParams()
  const navigate = useNavigate()

  const files = useProjectFiles(projectId)
  const deleteFile = useDeleteFile(projectId)
  const startGeneration = useStartGeneration(projectId)

  const [selectedIds, setSelectedIds] = useState<string[]>([])
  const [title, setTitle] = useState('')
  const [author, setAuthor] = useState('')
  const [course, setCourse] = useState('')
  const [date, setDate] = useState(todayLocal)
  const [instructions, setInstructions] = useState('')
  const [jobId, setJobId] = useState<string | null>(null)

  const job = useGenerationJob(jobId)

  // 생성이 끝나면 편집기로 넘어간다. reportId 가 null 인 경우가 계약상 허용되므로 확인한다.
  useEffect(() => {
    if (job.data?.status === 'COMPLETED' && job.data.reportId) {
      void navigate(`/reports/${job.data.reportId}`)
    }
  }, [job.data, navigate])

  const items = files.data?.items ?? []
  const selectable = items.filter(isAiInputFile)

  const toggle = (fileId: string) => {
    setSelectedIds((prev) =>
      prev.includes(fileId) ? prev.filter((id) => id !== fileId) : [...prev, fileId],
    )
  }

  const handleGenerate = (event: React.FormEvent) => {
    event.preventDefault()
    startGeneration.mutate(
      toGenerationRequest({
        fileIds: selectedIds,
        metadata: {
          title: title.trim(),
          ...(author.trim() && { author: author.trim() }),
          ...(course.trim() && { course: course.trim() }),
          ...(date && { date }),
        },
        instructions: instructions.trim(),
      }),
      { onSuccess: ({ jobId: id }) => setJobId(id) },
    )
  }

  const running = jobId !== null && job.data !== undefined && !isFinished(job.data.status)
  // instructions 만 계약상 필수(minLength 1)다. metadata 는 object 라 내부 필수 항목이 없다.
  const canSubmit = selectedIds.length > 0 && instructions.trim() !== '' && !running

  return (
    <main className="mx-auto max-w-3xl space-y-8 p-6">
      <UploadPanel projectId={projectId} />

      <section>
        <h2 className="text-lg font-semibold">분석할 파일 선택</h2>

        {files.isPending && <p className="mt-2 text-gray-500">불러오는 중…</p>}
        {files.error && (
          <p role="alert" className="mt-2 text-red-600">
            {toDisplayMessage(files.error)}
          </p>
        )}
        {files.data && items.length === 0 && (
          <p className="mt-2 text-gray-500">아직 올린 파일이 없습니다.</p>
        )}

        {items.length > 0 && (
          <ul className="mt-2 divide-y divide-gray-200">
            {items.map((file) => (
              <FileRow
                key={file.id}
                file={file}
                checked={selectedIds.includes(file.id)}
                onToggle={() => toggle(file.id)}
                onDelete={() => deleteFile.mutate(file.id)}
              />
            ))}
          </ul>
        )}

        {deleteFile.error && (
          <p role="alert" className="mt-2 text-sm text-red-600">
            {toDisplayMessage(deleteFile.error)}
          </p>
        )}

        {items.length > 0 && selectable.length === 0 && (
          <p className="mt-2 text-sm text-amber-700">
            AI 가 분석할 수 있는 파일이 없습니다. ZIP · MD · TXT · PNG · JPG 를 올려 주세요.
          </p>
        )}
      </section>

      <form onSubmit={handleGenerate} className="space-y-4">
        <h2 className="text-lg font-semibold">보고서 정보</h2>

        <Field id="title" label="제목" value={title} onChange={setTitle} />
        <Field id="author" label="작성자" value={author} onChange={setAuthor} />
        <Field id="course" label="과목" value={course} onChange={setCourse} />
        <Field id="date" label="날짜" type="date" value={date} onChange={setDate} />

        <div>
          <label htmlFor="instructions" className="block text-sm font-medium">
            작성 지시사항 <span className="text-red-600">*</span>
          </label>
          <textarea
            id="instructions"
            value={instructions}
            onChange={(e) => setInstructions(e.target.value)}
            rows={4}
            placeholder="어떤 내용을 강조할지, 어떤 형식으로 쓸지 적어 주세요."
            className="mt-1 w-full rounded border border-gray-300 px-3 py-2"
          />
        </div>

        <button
          type="submit"
          disabled={!canSubmit || startGeneration.isPending}
          className="rounded bg-gray-900 px-4 py-2 text-white disabled:opacity-40"
        >
          {startGeneration.isPending ? '요청 중…' : '보고서 생성'}
        </button>

        {startGeneration.error && (
          <p role="alert" className="text-sm text-red-600">
            {toDisplayMessage(startGeneration.error)}
          </p>
        )}
      </form>

      {jobId && <GenerationStatus job={job} onRetry={() => setJobId(null)} />}
    </main>
  )
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

function isFinished(status: string): boolean {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELED'
}

function Field({
  id,
  label,
  value,
  onChange,
  required,
  type = 'text',
}: {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  required?: boolean
  type?: 'text' | 'date'
}) {
  return (
    <div>
      <label htmlFor={id} className="block text-sm font-medium">
        {label} {required && <span className="text-red-600">*</span>}
      </label>
      <input
        id={id}
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="mt-1 w-full rounded border border-gray-300 px-3 py-2"
      />
    </div>
  )
}

function FileRow({
  file,
  checked,
  onToggle,
  onDelete,
}: {
  file: FileResponse
  checked: boolean
  onToggle: () => void
  onDelete: () => void
}) {
  const usable = isAiInputFile(file)

  return (
    <li className="flex items-center gap-3 py-2">
      <input
        type="checkbox"
        id={`file-${file.id}`}
        checked={checked}
        onChange={onToggle}
        disabled={!usable}
      />
      <label htmlFor={`file-${file.id}`} className="flex-1 truncate text-sm">
        {file.originalName}
        {!usable && <span className="ml-2 text-xs text-gray-500">AI 분석 대상 아님</span>}
      </label>
      <button
        type="button"
        onClick={onDelete}
        className="shrink-0 text-sm text-gray-500 hover:text-red-600"
      >
        삭제
      </button>
    </li>
  )
}

/**
 * 생성 진행 상태.
 *
 * `progress` 는 0·10·100 세 값뿐이라 퍼센트 진행바로 쓰면 10% 에서 한참 멈춘 것처럼 보인다.
 * 그래서 숫자 대신 `currentStage` 를 문구로 보여준다.
 */
const STAGE_LABELS: Record<string, string> = {
  QUEUED: '순서를 기다리는 중',
  CALLING_AI: 'AI 가 보고서를 작성하는 중',
  COMPLETED: '완료',
  FAILED: '실패',
  CANCELED: '취소됨',
}

function GenerationStatus({
  job,
  onRetry,
}: {
  job: ReturnType<typeof useGenerationJob>
  onRetry: () => void
}) {
  if (job.isPending) return <p className="text-gray-500">생성 작업을 확인하는 중…</p>

  if (job.error) {
    return (
      <div role="alert" className="space-y-2">
        <p className="text-red-600">{toDisplayMessage(job.error)}</p>
        <RetryButton onClick={onRetry} />
      </div>
    )
  }

  const data = job.data
  if (!data) return null

  if (data.status === 'FAILED' || data.status === 'CANCELED') {
    return (
      <div role="alert" className="space-y-2">
        <p className="font-medium text-red-600">
          {data.status === 'FAILED' ? '보고서 생성에 실패했습니다.' : '생성이 취소되었습니다.'}
        </p>
        {data.failureMessage && <p className="text-sm text-red-600">{data.failureMessage}</p>}
        <RetryButton onClick={onRetry} />
      </div>
    )
  }

  if (data.status === 'COMPLETED' && !data.reportId) {
    // 계약상 가능한 조합이다. 편집기로 보낼 수 없으니 그대로 알린다.
    return (
      <div role="alert" className="space-y-2">
        <p className="text-red-600">생성은 끝났지만 보고서를 찾을 수 없습니다.</p>
        <RetryButton onClick={onRetry} />
      </div>
    )
  }

  return (
    <p aria-live="polite" className="text-gray-700">
      {STAGE_LABELS[data.currentStage] ?? '진행 중'}…
    </p>
  )
}

function RetryButton({ onClick }: { onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="rounded border border-gray-300 px-3 py-1 text-sm hover:bg-gray-50"
    >
      다시 시도
    </button>
  )
}
