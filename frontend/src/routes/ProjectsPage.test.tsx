import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen } from '@testing-library/react'
import ProjectsPage from './ProjectsPage'
import { renderWithProviders } from '../test/renderWithProviders'
import { clearAccessToken, setAccessToken } from '../lib/auth/tokenStore'

// MemoryRouter 에서는 주소가 안 바뀌므로 이동 자체를 확인한다
const { navigateMock } = vi.hoisted(() => ({ navigateMock: vi.fn() }))
vi.mock('react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router')>()),
  useNavigate: () => navigateMock,
}))

let projectName = '프로젝트 A'
/** 요청 중 상태를 관찰하려고 POST 응답을 잡아두는 스위치 */
let deferPost = false
let releasePost: (() => void) | null = null
let activeProject = true
let trashedProject = false

function json(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

function project(id: string, name: string) {
  return {
    id,
    name,
    ownerId: 'user-1',
    createdAt: '2026-08-14T00:00:00+09:00',
    updatedAt: '2026-08-14T00:00:00+09:00',
  }
}

beforeEach(() => {
  navigateMock.mockClear()
  deferPost = false
  releasePost = null
  projectName = '프로젝트 A'
  activeProject = true
  trashedProject = false
  setAccessToken('test-access')
  vi.stubGlobal(
    'fetch',
    vi.fn((rawUrl: string, init?: RequestInit) => {
      const url = String(rawUrl)
      const method = init?.method ?? 'GET'

      if (url.includes('/api/auth/me')) return Promise.resolve(json({ name: '사용자' }))

      if (url.includes('/api/projects/trash')) {
        return Promise.resolve(
          json({
            items: trashedProject
              ? [{ id: 'p-1', name: projectName, deletedAt: '2026-08-14T00:00:00+09:00' }]
              : [],
            page: 0,
            size: 20,
            totalElements: trashedProject ? 1 : 0,
            totalPages: trashedProject ? 1 : 0,
          }),
        )
      }

      if (url.includes('/api/projects/p-1') && method === 'PUT') {
        projectName = JSON.parse(String(init?.body)).name
        return Promise.resolve(json(project('p-1', projectName)))
      }

      if (url.includes('/api/projects/p-1') && method === 'DELETE') {
        activeProject = false
        trashedProject = true
        return Promise.resolve(new Response(null, { status: 204 }))
      }

      if (url.includes('/api/projects/p-1/restore') && method === 'POST') {
        activeProject = true
        trashedProject = false
        return Promise.resolve(json(project('p-1', projectName)))
      }

      if (url.includes('/api/projects?')) {
        const page = url.includes('page=1') ? 1 : 0
        return Promise.resolve(
          json({
            items:
              page === 1
                ? [project('p-2', '프로젝트 B')]
                : activeProject
                  ? [project('p-1', projectName)]
                  : [],
            page,
            size: 20,
            totalElements: 2,
            totalPages: 2,
          }),
        )
      }

      if (url.endsWith('/api/projects') && method === 'POST') {
        if (!deferPost) return Promise.resolve(json({ projectId: 'p-new' }))
        return new Promise((resolve) => {
          releasePost = () => resolve(json({ projectId: 'p-new' }))
        })
      }

      return Promise.resolve(
        json({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
      )
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  clearAccessToken()
})

describe('ProjectsPage project management', () => {
  it('프로젝트 이름을 수정하고 휴지통으로 이동한다', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderWithProviders(<ProjectsPage />)

    expect(await screen.findByText('프로젝트 A')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '이름 수정' }))
    fireEvent.change(screen.getByDisplayValue('프로젝트 A'), { target: { value: '새 프로젝트' } })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByText('새 프로젝트')).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith(
      '/api/projects/p-1',
      expect.objectContaining({ method: 'PUT', body: JSON.stringify({ name: '새 프로젝트' }) }),
    )

    fireEvent.click(screen.getByRole('button', { name: '휴지통 이동' }))
    expect(confirm).toHaveBeenCalled()
    expect(
      await screen.findByText('아직 프로젝트가 없습니다. 위에서 만들어 주세요.'),
    ).toBeInTheDocument()
    expect(await screen.findByText('새 프로젝트')).toBeInTheDocument()
  })

  it('이름을 비우고 만들면 기본 이름으로 생성한다', async () => {
    renderWithProviders(<ProjectsPage />)

    expect(await screen.findByText('프로젝트 A')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '프로젝트 만들기' }))

    await vi.waitFor(() =>
      expect(fetch).toHaveBeenCalledWith(
        '/api/projects',
        expect.objectContaining({ method: 'POST', body: JSON.stringify({ name: '새 프로젝트' }) }),
      ),
    )
    // 만든 프로젝트로 이동해야 한다 (리뷰 지적)
    await vi.waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/projects/p-new'))
  })

  it('요청 중에는 만들기 버튼을 비활성화한다', async () => {
    deferPost = true
    renderWithProviders(<ProjectsPage />)

    expect(await screen.findByText('프로젝트 A')).toBeInTheDocument()
    const button = screen.getByRole('button', { name: '프로젝트 만들기' })
    expect(button).toBeEnabled()

    fireEvent.click(button)
    await vi.waitFor(() =>
      expect(screen.getByRole('button', { name: '만드는 중…' })).toBeDisabled(),
    )

    releasePost?.()
    await vi.waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/projects/p-new'))
  })

  it('휴지통 프로젝트를 복구한다', async () => {
    activeProject = false
    trashedProject = true
    renderWithProviders(<ProjectsPage />)

    expect(await screen.findAllByText('프로젝트 A')).toHaveLength(1)
    expect(screen.getByText(/삭제한 프로젝트와 파일은 30일/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '복구' }))

    expect(await screen.findByText('휴지통이 비어 있습니다.')).toBeInTheDocument()
    expect(await screen.findAllByText('프로젝트 A')).toHaveLength(1)
    expect(fetch).toHaveBeenCalledWith(
      '/api/projects/p-1/restore',
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('프로젝트 목록 페이지를 이동한다', async () => {
    renderWithProviders(<ProjectsPage />)

    expect(await screen.findByText('프로젝트 A')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }))

    expect(await screen.findByText('프로젝트 B')).toBeInTheDocument()
    expect(screen.getByText(/2\s*\/\s*2/)).toBeInTheDocument()
  })
})
