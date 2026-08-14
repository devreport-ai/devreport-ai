import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import PolicyPage from './PolicyPage'
import { renderWithProviders } from '../test/renderWithProviders'

describe('PolicyPage', () => {
  it('AI 전송과 실제 보관 기간을 고지한다', () => {
    renderWithProviders(<PolicyPage />)

    expect(screen.getByRole('heading', { name: '개인정보처리방침·이용약관' })).toBeInTheDocument()
    expect(screen.getByText(/AI Service와 Gemini에 전달될 수 있습니다/)).toBeInTheDocument()
    expect(screen.getByText(/휴지통에서 30일간 복구/)).toBeInTheDocument()
    expect(screen.getByText(/최대 90일 보관/)).toBeInTheDocument()
  })
})
