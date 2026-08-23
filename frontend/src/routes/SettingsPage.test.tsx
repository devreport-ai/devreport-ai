import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import SettingsPage from './SettingsPage'
import { clearAccessToken, setAccessToken } from '../lib/auth/tokenStore'
import { renderWithProviders } from '../test/renderWithProviders'

const { navigateMock } = vi.hoisted(() => ({ navigateMock: vi.fn() }))
vi.mock('react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router')>()),
  useNavigate: () => navigateMock,
}))

function json(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  navigateMock.mockClear()
  setAccessToken('test-access')
  vi.stubGlobal(
    'fetch',
    vi.fn((rawUrl: string) => {
      const url = String(rawUrl)
      if (url.includes('/api/auth/me')) return Promise.resolve(json({ name: '사용자' }))
      if (url.endsWith('/api/auth/password'))
        return Promise.resolve(new Response(null, { status: 204 }))
      return Promise.resolve(json({ items: [], totalElements: 0, totalPages: 0 }))
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
  clearAccessToken()
})

describe('SettingsPage', () => {
  it('shows client validation and does not submit invalid values', async () => {
    renderWithProviders(<SettingsPage />)

    expect(await screen.findByRole('heading', { name: '계정 설정' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }))

    expect(screen.getAllByText('비밀번호를 입력해 주세요.')).toHaveLength(2)
    expect(fetch).not.toHaveBeenCalledWith('/api/auth/password', expect.anything())
  })

  it('changes the password and returns to login after success', async () => {
    renderWithProviders(<SettingsPage />)

    fireEvent.change(await screen.findByLabelText('현재 비밀번호'), {
      target: { value: 'password123' },
    })
    fireEvent.change(screen.getByLabelText('새 비밀번호'), {
      target: { value: 'new-password123' },
    })
    fireEvent.change(screen.getByLabelText('새 비밀번호 확인'), {
      target: { value: 'new-password123' },
    })
    fireEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }))

    await waitFor(() =>
      expect(fetch).toHaveBeenCalledWith(
        '/api/auth/password',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ currentPassword: 'password123', newPassword: 'new-password123' }),
        }),
      ),
    )
    expect(navigateMock).toHaveBeenCalledWith('/login', { replace: true })
  })
})
