/**
 * 라우팅 스모크 테스트.
 *
 * 기능 검증이 목적이 아니라 라우트 표가 실제로 연결돼 있는지 확인하는 것이다.
 * 프로젝트 목록 화면은 API 를 호출하므로 fetch 를 가로채 빈 목록을 돌려준다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import App from './App'
import { renderWithProviders } from './test/renderWithProviders'
import { clearTokens, setTokens } from './lib/auth/tokenStore'

/** 보호된 화면을 테스트할 때 쓰는 로그인 상태. */
function loginAs() {
  setTokens({ accessToken: 'test-access', refreshToken: 'test-refresh' })
}

beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(() =>
      Promise.resolve(
        new Response(
          JSON.stringify({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    ),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
  clearTokens()
})

describe('App 라우팅', () => {
  it('로그인 상태로 / 에 들어가면 프로젝트 목록 화면을 보여준다', async () => {
    loginAs()
    renderWithProviders(<App />, { route: '/' })

    expect(await screen.findByRole('heading', { name: 'DevReport AI' })).toBeInTheDocument()
  })

  it('로그인 상태로 /reports/:reportId 에 들어가면 보고서 화면을 보여준다', async () => {
    loginAs()
    renderWithProviders(<App />, { route: '/reports/abc-123' })

    expect(await screen.findByRole('heading', { name: '보고서' })).toBeInTheDocument()
    // 생성 완료 후 이동이 제대로 됐는지 눈으로 확인할 수 있어야 한다.
    expect(screen.getByText('abc-123')).toBeInTheDocument()
  })

  it('정의되지 않은 주소로 들어가면 404 화면을 보여준다', async () => {
    renderWithProviders(<App />, { route: '/존재하지-않는-주소' })

    expect(
      await screen.findByRole('heading', { name: '페이지를 찾을 수 없습니다' }),
    ).toBeInTheDocument()
  })
})

describe('라우트 보호 (이슈 #61)', () => {
  it('미로그인으로 보호된 화면에 가면 로그인 화면으로 보낸다', async () => {
    renderWithProviders(<App />, { route: '/projects/abc' })

    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument()
  })

  it('로그인·회원가입 화면은 미로그인으로도 볼 수 있다', async () => {
    renderWithProviders(<App />, { route: '/signup' })

    expect(await screen.findByRole('heading', { name: '회원가입' })).toBeInTheDocument()
  })
})
