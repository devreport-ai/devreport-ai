/**
 * 라우팅 골격이 실제로 동작하는지 확인하는 최소 테스트.
 *
 * 기능 검증이 목적이 아니라, 테스트 환경 자체(jsdom, Testing Library,
 * setup 파일)가 제대로 물려 있는지 확인하는 것이 목적이다.
 * 이게 통과하면 이후 이슈에서 화면 테스트를 바로 쓸 수 있다.
 *
 * 실제 앱과 달리 MemoryRouter 를 쓰는 이유는 initialEntries 로
 * "이 주소로 들어온 상태"를 만들어 볼 수 있기 때문이다.
 */
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import App from './App'

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  )
}

describe('App 라우팅', () => {
  it('/ 로 들어가면 홈 화면을 보여준다', () => {
    renderAt('/')

    expect(screen.getByRole('heading', { name: 'DevReport AI' })).toBeInTheDocument()
  })

  it('정의되지 않은 주소로 들어가면 404 화면을 보여준다', () => {
    renderAt('/존재하지-않는-주소')

    expect(screen.getByRole('heading', { name: '페이지를 찾을 수 없습니다' })).toBeInTheDocument()
  })
})
