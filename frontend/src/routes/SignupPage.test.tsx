import { describe, expect, it } from 'vitest'
import { fireEvent, screen } from '@testing-library/react'
import { SignupPage } from './SignupPage'
import { renderWithProviders } from '../test/renderWithProviders'

describe('SignupPage', () => {
  it('정책 미동의 제출 시 오류와 접근성 상태를 표시한다', () => {
    renderWithProviders(<SignupPage />)

    fireEvent.click(screen.getByRole('button', { name: '가입하기' }))

    expect(screen.getByText('개인정보처리방침과 이용약관에 동의해야 합니다.')).toBeInTheDocument()
    expect(screen.getByRole('checkbox')).toHaveAttribute('aria-invalid', 'true')
  })
})
