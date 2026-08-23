import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import App from '../App'
import { renderWithProviders } from '../test/renderWithProviders'
import { clearAccessToken, setAccessToken } from '../lib/auth/tokenStore'

function json(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  setAccessToken('test-access')
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url.includes('/api/auth/me')) return Promise.resolve(json({ name: '사용자' }))
      if (url.endsWith('/api/ai/models')) {
        return Promise.resolve(
          json({
            items: [
              {
                provider: 'GEMINI',
                model: 'gemini-3.5-flash-lite',
                label: 'Gemini 3.5 Flash-Lite',
                serverDefault: true,
                available: true,
              },
              {
                provider: 'GEMINI',
                model: 'gemini-3.5-pro',
                label: 'Gemini 3.5 Pro',
                serverDefault: false,
                available: false,
              },
            ],
          }),
        )
      }
      if (url.endsWith('/api/projects/p-1')) {
        return Promise.resolve(json({ id: 'p-1', name: '졸업 프로젝트', ownerId: 'u-1' }))
      }
      if (url.includes('/api/projects/p-1/files')) {
        return Promise.resolve(
          json({ items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }),
        )
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
  clearAccessToken()
})

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

  it('모델 선택은 서버 기본 모델로 시작하고 키 없는 모델은 고를 수 없다', async () => {
    renderWithProviders(<App />, { route: '/projects/p-1' })

    const select = (await screen.findByLabelText('AI 모델')) as HTMLSelectElement
    await waitFor(() => expect(select.value).toBe('GEMINI/gemini-3.5-flash-lite'))
    const locked = screen.getByRole('option', { name: /Gemini 3.5 Pro/ }) as HTMLOptionElement
    expect(locked.disabled).toBe(true)
    expect(locked.textContent).toContain('API Key 등록 필요')
    expect(screen.getByRole('link', { name: 'API 키 추가에서 등록' })).toHaveAttribute(
      'href',
      '/api-keys',
    )
  })
})
