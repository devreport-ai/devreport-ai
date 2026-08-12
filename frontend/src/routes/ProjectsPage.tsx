/**
 * 프로젝트 목록 + 생성 화면.
 *
 * 계약상 프로젝트 생성에 필요한 필드는 `name` 하나뿐이다.
 */
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { useLogout, useMe } from '../features/auth/api'
import { useCreateProject, useProjects } from '../features/projects/api'
import { toDisplayMessage } from '../lib/api/errors'

export default function ProjectsPage() {
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const { data, isPending, error } = useProjects()
  const createProject = useCreateProject()
  const me = useMe()
  const logout = useLogout()

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

  return (
    <main className="mx-auto max-w-3xl p-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">DevReport AI</h1>
        <div className="flex items-center gap-3 text-sm text-gray-600">
          {me.data && <span>{me.data.name}</span>}
          <button
            type="button"
            onClick={() => logout.mutate()}
            disabled={logout.isPending}
            className="rounded border border-gray-300 px-3 py-1 hover:bg-gray-50 disabled:opacity-40"
          >
            로그아웃
          </button>
        </div>
      </div>

      <form onSubmit={handleCreate} className="mt-6 flex gap-2">
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
                <Link to={`/projects/${project.id}`} className="block py-3 hover:underline">
                  {project.name}
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  )
}
