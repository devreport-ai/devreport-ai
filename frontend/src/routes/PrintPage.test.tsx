/** 출력 페이지 — 렌더링·완료 신호·만료(404) 동작을 고정한다 (#39). */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import App from '../App'
import { renderWithProviders } from '../test/renderWithProviders'

function renderData() {
  return {
    exportId: 'exp-1',
    reportId: 'r-1',
    projectId: 'p-1',
    reportVersion: 3,
    document: {
      metadata: { title: '출력 보고서', author: '작성자' },
      sections: [
        {
          id: 'sec-1',
          title: '개요',
          blocks: [{ id: 'b-1', type: 'paragraph', content: '출력할 내용' }],
        },
      ],
    },
    templateId: 'compact',
    templateVersion: 1,
    presentationSettings: {},
    imageFileIds: [],
  }
}

beforeEach(() => {
  delete document.documentElement.dataset.printState
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('PrintPage', () => {
  it('스냅샷을 템플릿에 맞춰 그리고 완료 신호를 켠다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        // 계약: render token 은 X-Render-Token 헤더로 나가야 한다
        expect(new Headers(init?.headers).get('X-Render-Token')).toBe('tok-1')
        expect(url).toContain('/api/report-exports/exp-1/render-data')
        return Promise.resolve(
          new Response(JSON.stringify(renderData()), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        )
      }),
    )

    const { container } = renderWithProviders(<App />, { route: '/print/exp-1#token=tok-1' })

    expect(await screen.findByRole('heading', { name: '출력 보고서' })).toBeInTheDocument()
    expect(screen.getByText('출력할 내용')).toBeInTheDocument()
    // 저장된 템플릿이 적용된다
    expect(container.querySelector('.tpl-compact')).not.toBeNull()
    // 편집 UI 가 없어야 한다 (완료 조건: 편집 도구가 PDF 에 포함되지 않음)
    expect(screen.queryByRole('button')).toBeNull()
    // Backend Chromium 이 기다리는 신호
    await waitFor(() => expect(document.documentElement.dataset.printState).toBe('ready'))
  })

  it('만료(404)면 신호를 켜지 않는다 — Chromium 이 타임아웃으로 실패해야 한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({
              code: 'EXPORT_NOT_FOUND',
              message: '출력 정보를 찾을 수 없습니다.',
              details: null,
              timestamp: '2026-08-12T00:00:00+09:00',
            }),
            { status: 404, headers: { 'Content-Type': 'application/json' } },
          ),
        ),
      ),
    )

    renderWithProviders(<App />, { route: '/print/exp-9#token=expired' })

    expect(await screen.findByText(/불러올 수 없습니다/)).toBeInTheDocument()
    expect(document.documentElement.dataset.printState).toBeUndefined()
  })

  it('토큰이 없으면 요청 없이 안내만 보여준다', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    renderWithProviders(<App />, { route: '/print/exp-1' })

    expect(await screen.findByText(/출력 토큰이 없습니다/)).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
