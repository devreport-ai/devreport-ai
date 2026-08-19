import { describe, expect, it } from 'vitest'
import { fireEvent, screen } from '@testing-library/react'
import { TemplateChoicePanel } from './TemplateChoicePanel'
import type { useGenerationJob } from './api'
import { renderWithProviders } from '../../test/renderWithProviders'

const idleJob = {
  data: { status: 'RUNNING' },
  error: null,
  timedOut: false,
} as unknown as ReturnType<typeof useGenerationJob>

describe('TemplateChoicePanel', () => {
  it('최신 HTML 템플릿 12개와 분류 필터를 보여준다', () => {
    renderWithProviders(<TemplateChoicePanel job={idleJob} onRetry={() => undefined} />)

    expect(screen.getByRole('heading', { name: '보고서 템플릿을 선택하세요' })).toBeInTheDocument()
    expect(screen.getAllByText('HTML')).toHaveLength(12)
    expect(screen.getByText('모던 블루')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('tab', { name: '개발 문서' }))

    expect(screen.getByRole('tab', { name: '개발 문서' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.queryByText('모던 블루')).toBeNull()
  })
})
