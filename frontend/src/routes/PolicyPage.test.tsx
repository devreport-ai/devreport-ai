import { afterEach, describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import PolicyPage from './PolicyPage'
import { renderWithProviders } from '../test/renderWithProviders'
import { clearAccessToken, setAccessToken } from '../lib/auth/tokenStore'

afterEach(() => clearAccessToken())

describe('PolicyPage', () => {
  it('AI 전송과 실제 보관 기간을 고지한다', () => {
    renderWithProviders(<PolicyPage />)

    expect(screen.getByRole('heading', { name: '개인정보처리방침·이용약관' })).toBeInTheDocument()
    expect(screen.getByText(/AI Service에 전달될 수 있습니다/)).toBeInTheDocument()
    expect(screen.getByText(/휴지통에서 30일간 복구/)).toBeInTheDocument()
    expect(screen.getByText(/최대 90일 보관/)).toBeInTheDocument()
  })

  it('로그인 상태에서는 홈으로 돌아간다', () => {
    setAccessToken('test-access')
    renderWithProviders(<PolicyPage />)

    expect(screen.getByRole('link', { name: '프로젝트로 돌아가기' })).toHaveAttribute('href', '/')
  })

  it('주소에 앵커가 있어도 렌더에 실패하지 않는다', async () => {
    renderWithProviders(<PolicyPage />, { route: '/policies#privacy' })

    await new Promise((resolve) => setTimeout(resolve, 100))
    expect(screen.getByRole('heading', { name: '개인정보처리방침·이용약관' })).toBeInTheDocument()
  })

  it('로그인 전에는 로그인 화면으로 돌아간다', () => {
    renderWithProviders(<PolicyPage />)

    expect(screen.getByRole('link', { name: '로그인으로 돌아가기' })).toHaveAttribute(
      'href',
      '/login',
    )
  })
})
