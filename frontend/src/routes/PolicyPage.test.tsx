import { afterEach, describe, expect, it, vi } from 'vitest'
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
    expect(screen.getByRole('link', { name: 'DevReport AI' })).toHaveAttribute('href', '/')
  })

  it.each(['privacy', 'terms'])('주소의 #%s 앵커로 스크롤한다', async (anchor) => {
    // jsdom 에는 scrollIntoView 가 없어 직접 넣어 호출을 관찰한다
    const scrollIntoView = vi.fn()
    Element.prototype.scrollIntoView = scrollIntoView
    renderWithProviders(<PolicyPage />, { route: `/policies#${anchor}` })

    await vi.waitFor(() => expect(scrollIntoView).toHaveBeenCalledWith({ block: 'start' }))
    expect(scrollIntoView.mock.instances[0]).toBe(document.getElementById(anchor))
  })

  it('로그인 전에는 로그인 화면으로 돌아간다', () => {
    renderWithProviders(<PolicyPage />)

    expect(screen.getByRole('link', { name: '로그인으로 돌아가기' })).toHaveAttribute(
      'href',
      '/login',
    )
    expect(screen.getByRole('link', { name: 'DevReport AI' })).toHaveAttribute('href', '/login')
  })
})
