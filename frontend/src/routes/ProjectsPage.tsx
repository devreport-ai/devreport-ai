/**
 * 프로젝트 목록 + 생성 화면.
 *
 * 계약상 프로젝트 생성에 필요한 필드는 `name` 하나뿐이다.
 */
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { AppNav } from '../components/AppNav'
import {
  useCreateProject,
  useDeleteProject,
  useProjects,
  useRestoreProject,
  useTrashedProjects,
  useUpdateProject,
} from '../features/projects/api'
import { toDisplayMessage } from '../lib/api/errors'

const PAGE_SIZE = 20

export default function ProjectsPage() {
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [page, setPage] = useState(0)
  const [trashPage, setTrashPage] = useState(0)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [editingName, setEditingName] = useState('')
  const { data, isPending, isFetching, error } = useProjects(page, PAGE_SIZE)
  const trashed = useTrashedProjects(trashPage, PAGE_SIZE)
  const createProject = useCreateProject()
  const updateProject = useUpdateProject()
  const deleteProject = useDeleteProject()
  const restoreProject = useRestoreProject()

  const handleCreate = (event: React.FormEvent) => {
    event.preventDefault()
    const trimmed = name.trim()
    if (!trimmed) return

    createProject.mutate(
      { name: trimmed },
      {
        onSuccess: ({ projectId }) => {
          setName('')
          // 만든 직후 바로 업로드로 넘어가는 게 자연스럽다.
          void navigate(`/projects/${projectId}`)
        },
      },
    )
  }

  const handleRename = (event: React.FormEvent, projectId: string) => {
    event.preventDefault()
    const trimmed = editingName.trim()
    if (!trimmed) return

    updateProject.mutate({ projectId, name: trimmed }, { onSuccess: () => setEditingId(null) })
  }

  const handleDelete = (projectId: string, projectName: string) => {
    if (!window.confirm(`'${projectName}' 프로젝트를 휴지통으로 이동할까요?`)) return
    deleteProject.mutate(projectId, { onSuccess: () => setPage(0) })
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <AppNav screen="프로젝트" />
      <main className="mx-auto max-w-3xl p-6">
        <form onSubmit={handleCreate} className="flex gap-2">
          <label htmlFor="project-name" className="sr-only">
            프로젝트 이름
          </label>
          <input
            id="project-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="새 프로젝트 이름"
            maxLength={100}
            className="flex-1 rounded border border-gray-300 px-3 py-2"
          />
          <button
            type="submit"
            disabled={!name.trim() || createProject.isPending}
            className="rounded bg-gray-900 px-4 py-2 text-white disabled:opacity-40"
          >
            {createProject.isPending ? '만드는 중…' : '만들기'}
          </button>
        </form>

        {createProject.error && (
          <p role="alert" className="mt-2 text-sm text-red-600">
            {toDisplayMessage(createProject.error)}
          </p>
        )}

        <section className="mt-8">
          <h2 className="text-lg font-semibold">프로젝트</h2>

          {isPending && <p className="mt-2 text-gray-500">불러오는 중…</p>}

          {error && (
            <p role="alert" className="mt-2 text-red-600">
              {toDisplayMessage(error)}
            </p>
          )}

          {data && data.items.length === 0 && (
            <p className="mt-2 text-gray-500">아직 프로젝트가 없습니다. 위에서 만들어 주세요.</p>
          )}

          {data && data.items.length > 0 && (
            <ul className="mt-2 divide-y divide-gray-200">
              {data.items.map((project) => (
                <li key={project.id}>
                  {editingId === project.id ? (
                    <form
                      onSubmit={(event) => handleRename(event, project.id)}
                      className="flex gap-2 py-2"
                    >
                      <label htmlFor={`edit-project-${project.id}`} className="sr-only">
                        프로젝트 이름
                      </label>
                      <input
                        id={`edit-project-${project.id}`}
                        value={editingName}
                        onChange={(event) => setEditingName(event.target.value)}
                        maxLength={100}
                        className="min-w-0 flex-1 rounded border border-gray-300 px-3 py-2"
                      />
                      <button
                        type="submit"
                        disabled={!editingName.trim() || updateProject.isPending}
                        className="rounded bg-gray-900 px-3 py-1 text-sm text-white disabled:opacity-40"
                      >
                        저장
                      </button>
                      <button
                        type="button"
                        onClick={() => setEditingId(null)}
                        className="rounded border border-gray-300 px-3 py-1 text-sm"
                      >
                        취소
                      </button>
                    </form>
                  ) : (
                    <div className="flex items-center gap-3 py-2">
                      <Link
                        to={`/projects/${project.id}`}
                        className="min-w-0 flex-1 py-1 hover:underline"
                      >
                        {project.name}
                      </Link>
                      <button
                        type="button"
                        onClick={() => {
                          setEditingId(project.id)
                          setEditingName(project.name)
                        }}
                        className="shrink-0 rounded border border-gray-300 px-2 py-1 text-sm"
                      >
                        이름 수정
                      </button>
                      <button
                        type="button"
                        onClick={() => handleDelete(project.id, project.name)}
                        disabled={deleteProject.isPending}
                        className="shrink-0 rounded border border-gray-300 px-2 py-1 text-sm text-gray-600 disabled:opacity-40"
                      >
                        휴지통 이동
                      </button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}

          {updateProject.error && (
            <p role="alert" className="mt-2 text-sm text-red-600">
              {toDisplayMessage(updateProject.error)}
            </p>
          )}
          {deleteProject.error && (
            <p role="alert" className="mt-2 text-sm text-red-600">
              {toDisplayMessage(deleteProject.error)}
            </p>
          )}

          {data && (
            <Pagination
              label="프로젝트 페이지 이동"
              page={page}
              totalPages={data.totalPages}
              isFetching={isFetching}
              onPageChange={setPage}
            />
          )}
        </section>

        <section className="mt-8 rounded-lg border border-gray-200 bg-white p-5">
          <h2 className="text-lg font-semibold">휴지통</h2>
          <p className="mt-1 text-sm text-gray-600">
            삭제한 프로젝트와 파일은 30일 동안 복구할 수 있으며 이후 완전히 삭제됩니다.
          </p>

          {trashed.isPending && <p className="mt-2 text-gray-500">휴지통을 불러오는 중…</p>}
          {trashed.error && (
            <p role="alert" className="mt-2 text-red-600">
              {toDisplayMessage(trashed.error)}
            </p>
          )}
          {trashed.data && trashed.data.items.length === 0 && (
            <p className="mt-2 text-gray-500">휴지통이 비어 있습니다.</p>
          )}
          {trashed.data && trashed.data.items.length > 0 && (
            <ul className="mt-2 divide-y divide-gray-200">
              {trashed.data.items.map((project) => (
                <li key={project.id} className="flex items-center gap-3 py-2">
                  <span className="min-w-0 flex-1">
                    <span className="block truncate">{project.name}</span>
                    <time dateTime={project.deletedAt} className="block text-sm text-gray-500">
                      {formatDate(project.deletedAt)} 삭제
                    </time>
                  </span>
                  <button
                    type="button"
                    onClick={() =>
                      restoreProject.mutate(project.id, { onSuccess: () => setTrashPage(0) })
                    }
                    disabled={restoreProject.isPending}
                    className="shrink-0 rounded border border-gray-300 px-3 py-1 text-sm disabled:opacity-40"
                  >
                    복구
                  </button>
                </li>
              ))}
            </ul>
          )}
          {restoreProject.error && (
            <p role="alert" className="mt-2 text-sm text-red-600">
              {toDisplayMessage(restoreProject.error)}
            </p>
          )}

          {trashed.data && (
            <Pagination
              label="휴지통 페이지 이동"
              page={trashPage}
              totalPages={trashed.data.totalPages}
              isFetching={trashed.isFetching}
              onPageChange={setTrashPage}
            />
          )}
        </section>
      </main>
    </div>
  )
}

function Pagination({
  label,
  page,
  totalPages,
  isFetching,
  onPageChange,
}: {
  label: string
  page: number
  totalPages: number
  isFetching: boolean
  onPageChange: (page: number) => void
}) {
  if (totalPages <= 1) return null

  return (
    <nav aria-label={label} className="mt-4 flex items-center justify-center gap-3">
      <button
        type="button"
        onClick={() => onPageChange(page - 1)}
        disabled={page === 0 || isFetching}
        className="rounded border border-gray-300 px-3 py-1 text-sm disabled:opacity-40"
      >
        이전 페이지
      </button>
      <span className="text-sm text-gray-600">
        {page + 1} / {totalPages}
      </span>
      <button
        type="button"
        onClick={() => onPageChange(page + 1)}
        disabled={page + 1 >= totalPages || isFetching}
        className="rounded border border-gray-300 px-3 py-1 text-sm disabled:opacity-40"
      >
        다음 페이지
      </button>
    </nav>
  )
}

function formatDate(value: string): string {
  return new Date(value).toLocaleString('ko-KR')
}
