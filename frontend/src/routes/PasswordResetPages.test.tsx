import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen } from '@testing-library/react'
import { ForgotPasswordPage } from './ForgotPasswordPage'
import { LoginPage } from './LoginPage'
import { ResetPasswordPage } from './ResetPasswordPage'
import { renderWithProviders } from '../test/renderWithProviders'

afterEach(() => {
  vi.unstubAllGlobals()
  window.history.replaceState({}, '', '/')
})

describe('Password reset pages', () => {
  it('로그인 화면에서 비밀번호 찾기로 진입할 수 있다', () => {
    renderWithProviders(<LoginPage />, { route: '/login' })
    expect(screen.getByRole('link', { name: '비밀번호를 잊으셨나요?' })).toHaveAttribute(
      'href',
      '/forgot-password',
    )
  })

  it('계정 존재 여부를 구분하지 않는 요청 완료 화면을 표시한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({
              message: '가입된 이메일이라면 비밀번호 재설정 링크를 전송했습니다.',
            }),
            { status: 202, headers: { 'Content-Type': 'application/json' } },
          ),
        ),
      ),
    )
    renderWithProviders(<ForgotPasswordPage />, { route: '/forgot-password' })

    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'user@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: '재설정 링크 받기' }))

    expect(await screen.findByText('이메일을 확인해 주세요')).toBeInTheDocument()
    expect(
      screen.getByText('가입된 이메일이라면 비밀번호 재설정 링크를 전송했습니다.'),
    ).toBeInTheDocument()
  })

  it('fragment 토큰으로 비밀번호를 재설정하고 주소에서 토큰을 제거한다', async () => {
    window.history.replaceState({}, '', '/reset-password#token=valid-token')
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response(null, { status: 204 }))),
    )
    renderWithProviders(<ResetPasswordPage />, { route: '/reset-password' })

    expect(window.location.hash).toBe('')
    fireEvent.change(screen.getByLabelText('새 비밀번호'), {
      target: { value: 'new-password123' },
    })
    fireEvent.change(screen.getByLabelText('새 비밀번호 확인'), {
      target: { value: 'new-password123' },
    })
    fireEvent.click(screen.getByRole('button', { name: '비밀번호 재설정' }))

    expect(await screen.findByText('비밀번호가 변경되었습니다')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '로그인하기' })).toHaveAttribute('href', '/login')
  })

  it('토큰이 없으면 새 링크 요청을 안내한다', () => {
    window.history.replaceState({}, '', '/reset-password')
    renderWithProviders(<ResetPasswordPage />, { route: '/reset-password' })

    expect(screen.getByText('유효하지 않은 링크입니다')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '새 링크 요청하기' })).toHaveAttribute(
      'href',
      '/forgot-password',
    )
  })

  it('비밀번호 최소 길이를 Unicode 문자 수로 검사한다', () => {
    window.history.replaceState({}, '', '/reset-password#token=valid-token')
    renderWithProviders(<ResetPasswordPage />, { route: '/reset-password' })

    fireEvent.change(screen.getByLabelText('새 비밀번호'), {
      target: { value: '😀'.repeat(4) },
    })
    fireEvent.change(screen.getByLabelText('새 비밀번호 확인'), {
      target: { value: '😀'.repeat(4) },
    })
    fireEvent.click(screen.getByRole('button', { name: '비밀번호 재설정' }))

    expect(screen.getByText('비밀번호는 8자 이상이어야 합니다.')).toBeInTheDocument()
  })
})
