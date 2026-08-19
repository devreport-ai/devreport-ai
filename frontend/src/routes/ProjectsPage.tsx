/**
 * 프로젝트 목록 + 생성 화면.
 *
 * 계약상 프로젝트 생성에 필요한 필드는 `name` 하나뿐이다.
 */
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { AppNav } from '../components/AppNav'
import { PageHeader, SurfaceCard } from '../components/ui'
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
    <div className="app-page">
      <AppNav screen="프로젝트" />
      <main className="app-content">
        <PageHeader
          eyebrow="WORKSPACE"
          title="프로젝트"
          description="개발 자료를 모으고 AI 보고서 작업을 이어가세요."
        />

        <SurfaceCard className="create-project-card">
          <div className="create-project-card__heading">
            <div>
              <h2>프로젝트 시작</h2>
              <p>프로젝트를 만든 뒤 자료를 업로드할 수 있습니다.</p>
            </div>
          </div>
          <form onSubmit={handleCreate} className="create-project-form">
            <label htmlFor="project-name" className="sr-only">
              프로젝트 이름
            </label>
            <input
              id="project-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="새 프로젝트 이름"
              maxLength={100}
              className="field-control"
            />
            <button
              type="submit"
              disabled={!name.trim() || createProject.isPending}
              className="primary-button"
            >
              {createProject.isPending ? '만드는 중…' : '만들기'}
            </button>
          </form>

          {createProject.error && (
            <p role="alert" className="inline-alert">
              {toDisplayMessage(createProject.error)}
            </p>
          )}
        </SurfaceCard>

        <section>
          <div className="section-heading">
            <h2>프로젝트</h2>
            {data && <p>{data.totalElements}개 프로젝트</p>}
          </div>

          {isPending && <p className="inline-hint">불러오는 중…</p>}

          {error && (
            <p role="alert" className="inline-alert">
              {toDisplayMessage(error)}
            </p>
          )}

          {data && data.items.length === 0 && (
            <p className="inline-hint">아직 프로젝트가 없습니다. 위에서 만들어 주세요.</p>
          )}

          {data && data.items.length > 0 && (
            <ul className="project-card-grid">
              {data.items.map((project) => (
                <li key={project.id} className="project-card">
                  {editingId === project.id ? (
                    <form
                      onSubmit={(event) => handleRename(event, project.id)}
                      className="project-card__edit"
                    >
                      <label htmlFor={`edit-project-${project.id}`} className="sr-only">
                        프로젝트 이름
                      </label>
                      <input
                        id={`edit-project-${project.id}`}
                        value={editingName}
                        onChange={(event) => setEditingName(event.target.value)}
                        maxLength={100}
                        className="field-control"
                      />
                      <button
                        type="submit"
                        disabled={!editingName.trim() || updateProject.isPending}
                        className="primary-button"
                      >
                        저장
                      </button>
                      <button
                        type="button"
                        onClick={() => setEditingId(null)}
                        className="secondary-button"
                      >
                        취소
                      </button>
                    </form>
                  ) : (
                    <>
                      <div className="project-card__top">
                        <span className="project-card__icon" aria-hidden>
                          {project.name.slice(0, 1).toUpperCase()}
                        </span>
                        <span className="project-card__date">{formatDate(project.updatedAt)}</span>
                      </div>
                      <Link to={`/projects/${project.id}`} className="project-card__title">
                        {project.name}
                      </Link>
                      <div className="project-card__actions">
                        <button
                          type="button"
                          onClick={() => {
                            setEditingId(project.id)
                            setEditingName(project.name)
                          }}
                        >
                          이름 수정
                        </button>
                        <button
                          type="button"
                          onClick={() => handleDelete(project.id, project.name)}
                          disabled={deleteProject.isPending}
                        >
                          휴지통 이동
                        </button>
                      </div>
                    </>
                  )}
                </li>
              ))}
            </ul>
          )}

          {updateProject.error && (
            <p role="alert" className="inline-alert">
              {toDisplayMessage(updateProject.error)}
            </p>
          )}
          {deleteProject.error && (
            <p role="alert" className="inline-alert">
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

        <SurfaceCard className="trash-section">
          <div className="section-heading">
            <h2>휴지통</h2>
            <p>30일 보관</p>
          </div>
          <p className="trash-section__description">
            삭제한 프로젝트와 파일은 30일 동안 복구할 수 있으며 이후 완전히 삭제됩니다.
          </p>

          {trashed.isPending && <p className="inline-hint">휴지통을 불러오는 중…</p>}
          {trashed.error && (
            <p role="alert" className="inline-alert">
              {toDisplayMessage(trashed.error)}
            </p>
          )}
          {trashed.data && trashed.data.items.length === 0 && (
            <p className="inline-hint">휴지통이 비어 있습니다.</p>
          )}
          {trashed.data && trashed.data.items.length > 0 && (
            <ul className="m-0 list-none p-0">
              {trashed.data.items.map((project) => (
                <li key={project.id} className="trash-row">
                  <span>
                    <span className="trash-row__name">{project.name}</span>
                    <time dateTime={project.deletedAt}>{formatDate(project.deletedAt)} 삭제</time>
                  </span>
                  <button
                    type="button"
                    onClick={() =>
                      restoreProject.mutate(project.id, { onSuccess: () => setTrashPage(0) })
                    }
                    disabled={restoreProject.isPending}
                  >
                    복구
                  </button>
                </li>
              ))}
            </ul>
          )}
          {restoreProject.error && (
            <p role="alert" className="inline-alert">
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
        </SurfaceCard>
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
    <nav aria-label={label} className="pagination">
      <button
        type="button"
        onClick={() => onPageChange(page - 1)}
        disabled={page === 0 || isFetching}
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
      >
        다음 페이지
      </button>
    </nav>
  )
}

function formatDate(value: string): string {
  return new Date(value).toLocaleString('ko-KR')
}
