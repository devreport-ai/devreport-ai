/**
 * API Key 관리 화면 — 키 등록·삭제 흐름과 "키 원문을 다시 보여주지 않는다"는 규칙을 확인한다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import ApiKeysPage from './ApiKeysPage'
import { renderWithProviders } from '../test/renderWithProviders'
import { clearAccessToken, setAccessToken } from '../lib/auth/tokenStore'

const SECRET = 'AIzaSyUserKeyForSettingsTest-1234'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const models = {
  items: [
    {
      provider: 'GEMINI',
      model: 'gemini-3.5-flash-lite',
      label: 'Gemini 3.5 Flash-Lite',
      serverDefault: true,
      available: true,
      usesUserKey: false,
    },
    {
      provider: 'GEMINI',
      model: 'gemini-3.7-flash',
      label: 'Gemini 3.7 Flash',
      serverDefault: false,
      available: false,
      usesUserKey: false,
    },
  ],
}

let registered: boolean
let requests: Array<{ url: string; method: string; body: string | null }>

beforeEach(() => {
  setAccessToken('test-access')
  registered = false
  requests = []
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      requests.push({ url, method, body: typeof init?.body === 'string' ? init.body : null })
      if (url.endsWith('/api/ai/models')) return Promise.resolve(json(models))
      if (url.endsWith('/api/auth/me')) {
        return Promise.resolve(json({ id: 'u', email: 'a@b.c', name: '사용자', createdAt: '' }))
      }
      if (url.endsWith('/api/me/ai-credentials')) {
        return Promise.resolve(
          json({
            items: registered
              ? [
                  {
                    provider: 'GEMINI',
                    keyHint: '****1234',
                    verifiedAt: '2026-08-23T00:00:00Z',
                    createdAt: '2026-08-23T00:00:00Z',
                    updatedAt: '2026-08-23T00:00:00Z',
                  },
                ]
              : [],
          }),
        )
      }
      if (url.endsWith('/api/me/ai-credentials/GEMINI') && method === 'PUT') {
        const body = JSON.parse(init?.body as string) as { apiKey: string }
        if (body.apiKey.startsWith('invalid')) {
          return Promise.resolve(
            json(
              {
                code: 'AI_CREDENTIAL_INVALID',
                message: 'API Key가 올바르지 않습니다.',
                details: null,
                timestamp: '2026-08-23T00:00:00Z',
              },
              400,
            ),
          )
        }
        registered = true
        return Promise.resolve(
          json({
            provider: 'GEMINI',
            keyHint: '****1234',
            verifiedAt: '2026-08-23T00:00:00Z',
            createdAt: '2026-08-23T00:00:00Z',
            updatedAt: '2026-08-23T00:00:00Z',
          }),
        )
      }
      if (url.endsWith('/api/me/ai-credentials/GEMINI') && method === 'DELETE') {
        registered = false
        return Promise.resolve(new Response(null, { status: 204 }))
      }
      return Promise.resolve(json({ items: [] }))
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
  clearAccessToken()
  globalThis.localStorage?.clear()
  sessionStorage.clear()
})

describe('ApiKeysPage', () => {
  it('allowlist 의 provider 별 카드를 보여주고 키를 등록하면 힌트만 표시한다', async () => {
    renderWithProviders(<ApiKeysPage />, { route: '/ai-keys' })

    expect(await screen.findByRole('heading', { name: 'Google Gemini' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Anthropic Claude' })).not.toBeInTheDocument()
    expect(screen.getByText('미등록')).toBeInTheDocument()

    const input = screen.getByLabelText('API Key')
    expect(input).toHaveAttribute('type', 'password')
    fireEvent.change(input, { target: { value: SECRET } })
    fireEvent.click(screen.getByRole('button', { name: '등록' }))

    expect(await screen.findByText(/등록됨 \*\*\*\*1234/)).toBeInTheDocument()
    expect(screen.getByLabelText('새 API Key 로 교체')).toHaveValue('')
    // 키 원문은 화면 어디에도 남지 않는다
    expect(document.body.textContent).not.toContain(SECRET)
    // Node 26 + jsdom 조합에서는 localStorage 전역이 비어 있을 수 있어 옵셔널로 확인한다
    expect(globalThis.localStorage?.length ?? 0).toBe(0)
    expect(sessionStorage.length).toBe(0)
    const put = requests.find((request) => request.method === 'PUT')
    expect(put?.body).toBe(JSON.stringify({ apiKey: SECRET }))

    fireEvent.click(screen.getByRole('button', { name: '삭제' }))
    await waitFor(() => expect(screen.getByText('미등록')).toBeInTheDocument())
    expect(requests.some((request) => request.method === 'DELETE')).toBe(true)
  })

  it('서버가 키를 거부하면 오류 문구를 보여주고 등록 상태를 바꾸지 않는다', async () => {
    renderWithProviders(<ApiKeysPage />, { route: '/ai-keys' })

    fireEvent.change(await screen.findByLabelText('API Key'), {
      target: { value: 'invalid-key-value-0000' },
    })
    fireEvent.click(screen.getByRole('button', { name: '등록' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('API Key가 올바르지 않습니다.')
    expect(screen.getByText('미등록')).toBeInTheDocument()
  })
})
