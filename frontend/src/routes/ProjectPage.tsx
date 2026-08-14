/**
 * 프로젝트 화면 — 업로드부터 생성 요청·진행 상태까지 한 페이지에서 처리한다.
 *
 * 생성 진행을 별도 페이지로 빼지 않은 이유: 사용자가 파일 목록을 보면서 기다리는 게
 * 자연스럽고, 실패했을 때 같은 화면에서 바로 다시 요청할 수 있어야 하기 때문이다.
 *
 * 생성을 요청하면 곧바로 템플릿 선택으로 넘어간다(#36). 생성은 뒤에서 돌지만
 * 티를 내지 않는다 — 고르는 행위가 대기 시간을 대신한다.
 */
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { AppNav } from '../components/AppNav'
import { UploadPanel } from '../features/files/UploadPanel'
import { useDeleteFile, useProjectFiles } from '../features/files/api'
import { useGenerationJob, useStartGeneration } from '../features/generation/api'
import { TemplateChoicePanel } from '../features/generation/TemplateChoicePanel'
import { findTemplate } from '../features/report/templates'
import { useProject, useProjectReports } from '../features/projects/api'
import { toDisplayMessage } from '../lib/api/errors'
import { isAiInputFile, type FileResponse } from '../lib/contracts/types'

export default function ProjectPage() {
  const { projectId = '' } = useParams()

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
  const [jobId, setJobId] = useState<string | null>(null)

  const job = useGenerationJob(jobId)

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
      },
      { onSuccess: ({ jobId: id }) => setJobId(id) },
    )
  }

  // timedOut 을 빼면 상한에 걸린 뒤에도 running 이 true 로 굳어 버튼이 영구 비활성이 된다.
  const running =
    jobId !== null && job.data !== undefined && !isFinished(job.data.status) && !job.timedOut
  // 계약상 필수는 fileIds(1개 이상)·metadata.title·instructions 세 가지다.
  const canSubmit =
    selectedIds.length > 0 && title.trim() !== '' && instructions.trim() !== '' && !running

  return (
    <div className="min-h-screen bg-gray-50">
      <AppNav screen={project.data?.name ?? '보고서 만들기'} />
      <main className="mx-auto max-w-3xl space-y-5 p-6">
        <section className="rounded-lg border border-gray-200 bg-white p-5">
          {project.isPending && <p className="text-gray-500">프로젝트를 불러오는 중…</p>}
          {project.error && (
            <p role="alert" className="text-red-600">
              {toDisplayMessage(project.error)}
            </p>
          )}
          {project.data && (
            <>
              <h1 className="text-xl font-semibold">{project.data.name}</h1>
              <p className="mt-1 text-sm text-gray-500">
                프로젝트 자료를 관리하고 보고서를 다시 열 수 있습니다.
              </p>
            </>
          )}
        </section>

        <ReportList page={reportPage} reports={reports} onPageChange={setReportPage} />

        <section className="rounded-lg border border-gray-200 bg-white p-5">
          <UploadPanel projectId={projectId} />
        </section>

        <section className="rounded-lg border border-gray-200 bg-white p-5">
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

        <section className="rounded-lg border border-gray-200 bg-white p-5">
          {jobId !== null ? (
            <TemplateChoicePanel job={job} onRetry={() => setJobId(null)} />
          ) : (
            <form onSubmit={handleGenerate} className="space-y-4">
              <h2 className="text-lg font-semibold">보고서 정보</h2>

              <Field id="title" label="제목" required value={title} onChange={setTitle} />
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
          )}
        </section>
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
    <section
      aria-labelledby="report-list-heading"
      className="rounded-lg border border-gray-200 bg-white p-5"
    >
      <h2 id="report-list-heading" className="text-lg font-semibold">
        기존 보고서
      </h2>

      {reports.isPending && <p className="mt-2 text-gray-500">보고서를 불러오는 중…</p>}
      {reports.error && (
        <p role="alert" className="mt-2 text-red-600">
          {toDisplayMessage(reports.error)}
        </p>
      )}
      {data && data.items.length === 0 && (
        <p className="mt-2 text-gray-500">
          {data.totalElements === 0
            ? '아직 생성한 보고서가 없습니다.'
            : '이 페이지에 보고서가 없습니다.'}
        </p>
      )}

      {data && data.items.length > 0 && (
        <ul className="mt-2 divide-y divide-gray-200">
          {data.items.map((report) => (
            <li key={report.id}>
              <Link to={`/reports/${report.id}`} className="block py-3 hover:bg-gray-50">
                <span className="font-medium">보고서 {report.id.slice(0, 8)}</span>
                <span className="mt-1 flex flex-wrap gap-x-3 text-sm text-gray-500">
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
        <nav
          aria-label="보고서 페이지 이동"
          className="mt-4 flex items-center justify-center gap-3"
        >
          <button
            type="button"
            onClick={() => onPageChange(page - 1)}
            disabled={page === 0 || reports.isFetching}
            className="rounded border border-gray-300 px-3 py-1 text-sm disabled:opacity-40"
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
            className="rounded border border-gray-300 px-3 py-1 text-sm disabled:opacity-40"
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
