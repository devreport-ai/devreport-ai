import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import App from '../App'
import { renderWithProviders } from '../test/renderWithProviders'
import { clearAccessToken, setAccessToken } from '../lib/auth/tokenStore'

let filesResponse: unknown
const scrollIntoView = vi.fn()

function json(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  setAccessToken('test-access')
  filesResponse = { items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
    configurable: true,
    value: scrollIntoView,
  })
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url.includes('/api/auth/me')) return Promise.resolve(json({ name: '사용자' }))
      if (url.endsWith('/api/projects/p-1')) {
        return Promise.resolve(json({ id: 'p-1', name: '졸업 프로젝트', ownerId: 'u-1' }))
      }
      if (url.includes('/api/projects/p-1/files')) {
        return Promise.resolve(json(filesResponse))
      }
      if (url.includes('page=1&size=10')) {
        return Promise.resolve(
          json({ items: [], page: 1, size: 10, totalElements: 1, totalPages: 2 }),
        )
      }
      if (url.includes('/api/projects/p-1/reports')) {
        return Promise.resolve(
          json({
            items: [
              {
                id: 'report-1',
                templateId: 'github',
                templateVersion: 1,
                version: 3,
                updatedAt: '2026-08-14T00:00:00+09:00',
              },
            ],
            page: 0,
            size: 10,
            totalElements: 1,
            totalPages: 2,
          }),
        )
      }
      return Promise.resolve(
        json({ items: [], page: 0, size: 10, totalElements: 0, totalPages: 0 }),
      )
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
  scrollIntoView.mockReset()
  clearAccessToken()
})

function addSelectableFile() {
  filesResponse = {
    items: [
      {
        id: 'file-1',
        originalName: 'analysis.md',
        contentType: 'text/markdown',
        size: 10,
      },
    ],
    page: 0,
    size: 100,
    totalElements: 1,
    totalPages: 1,
  }
}

async function selectFile() {
  await waitFor(() => expect(document.getElementById('file-file-1')).toBeInTheDocument())
  fireEvent.click(document.getElementById('file-file-1') as HTMLInputElement)
}

describe('ProjectPage report list', () => {
  it('프로젝트명과 기존 보고서를 보여주고 페이지를 이동한다', async () => {
    renderWithProviders(<App />, { route: '/projects/p-1' })

    expect(await screen.findByRole('heading', { name: '졸업 프로젝트' })).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: /보고서 report-1/ })).toHaveAttribute(
      'href',
      '/reports/report-1',
    )
    expect(screen.getByText('GitHub 문서')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }))

    expect(await screen.findByText('이 페이지에 보고서가 없습니다.')).toBeInTheDocument()
    expect(screen.getByText(/2\s*\/\s*2/)).toBeInTheDocument()
  })

  it('생성 버튼을 누르면 필수 항목 오류를 표시하고 첫 누락 항목으로 이동한다', async () => {
    renderWithProviders(<App />, { route: '/projects/p-1' })

    await screen.findByRole('heading', { name: '졸업 프로젝트' })
    const button = screen.getByRole('button', { name: '보고서 생성' })

    expect(button).toBeEnabled()
    fireEvent.click(button)

    expect(screen.getByText('분석할 파일을 1개 이상 선택해 주세요.')).toBeInTheDocument()
    expect(screen.getByText('제목을 입력해 주세요.')).toBeInTheDocument()
    expect(screen.getByText('작성 지시사항을 입력해 주세요.')).toBeInTheDocument()
    expect(screen.getByText('자료 전달 안내를 확인해 주세요.')).toBeInTheDocument()
    expect(document.activeElement).toHaveAttribute('id', 'file-selection')
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'center' })
    expect(document.getElementById('file-selection')).toHaveAttribute(
      'aria-describedby',
      'file-selection-error',
    )
  })

  it('파일을 선택하면 제목으로 이동한다', async () => {
    addSelectableFile()
    renderWithProviders(<App />, { route: '/projects/p-1' })
    await selectFile()

    fireEvent.click(screen.getByRole('button', { name: '보고서 생성' }))

    expect(document.activeElement).toHaveAttribute('id', 'title')
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'center' })
  })

  it('선택 가능한 파일이 있으면 첫 체크박스에 파일 오류를 연결한다', async () => {
    addSelectableFile()
    renderWithProviders(<App />, { route: '/projects/p-1' })
    await waitFor(() => expect(document.getElementById('file-file-1')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '보고서 생성' }))

    const fileInput = document.getElementById('file-file-1')
    expect(document.activeElement).toBe(fileInput)
    expect(fileInput).toHaveAttribute('aria-invalid', 'true')
    expect(fileInput).toHaveAttribute('aria-describedby', 'file-selection-error')
  })

  it('제목을 입력하면 작성 지시사항으로 이동한다', async () => {
    addSelectableFile()
    renderWithProviders(<App />, { route: '/projects/p-1' })
    await selectFile()
    fireEvent.change(screen.getByLabelText(/제목/), { target: { value: '보고서 제목' } })

    fireEvent.click(screen.getByRole('button', { name: '보고서 생성' }))

    expect(document.activeElement).toHaveAttribute('id', 'instructions')
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'center' })
  })

  it('제목과 지시사항을 입력하면 자료 전달 동의로 이동한다', async () => {
    addSelectableFile()
    renderWithProviders(<App />, { route: '/projects/p-1' })
    await selectFile()
    fireEvent.change(screen.getByLabelText(/제목/), { target: { value: '보고서 제목' } })
    fireEvent.change(screen.getByLabelText(/작성 지시사항/), {
      target: { value: '핵심 내용을 정리해 주세요.' },
    })

    fireEvent.click(screen.getByRole('button', { name: '보고서 생성' }))

    expect(document.activeElement).toHaveAttribute('id', 'policy-agreement')
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'center' })
  })
})
