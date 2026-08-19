import { describe, expect, it, vi } from 'vitest'
import { fireEvent, screen } from '@testing-library/react'
import { Route, Routes } from 'react-router'
import { TemplateChoicePanel } from './TemplateChoicePanel'
import type { useGenerationJob } from './api'
import { renderWithProviders } from '../../test/renderWithProviders'

const idleJob = {
  data: { status: 'PROCESSING', progress: 10 },
  error: null,
  timedOut: false,
} as unknown as ReturnType<typeof useGenerationJob>

describe('TemplateChoicePanel', () => {
  it('최신 HTML 템플릿 12개와 분류 필터를 보여준다', () => {
    const { container } = renderWithProviders(
      <TemplateChoicePanel job={idleJob} onRetry={() => undefined} />,
    )

    expect(screen.getByRole('heading', { name: '보고서 템플릿을 선택하세요' })).toBeInTheDocument()
    expect(screen.getAllByText('HTML')).toHaveLength(12)
    expect(container.querySelectorAll('[class*="template-preview--id-"]')).toHaveLength(12)
    expect(screen.getByText('모던 블루')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '개발 문서' }))

    expect(screen.getByRole('button', { name: '개발 문서' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(screen.queryByText('모던 블루')).toBeNull()
  })

  it('확정 상태와 서버 진행률을 복구한다', () => {
    renderWithProviders(<TemplateChoicePanel job={idleJob} recovered onRetry={() => undefined} />)

    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '10')
    expect(screen.getByRole('button', { name: '생성 중…' })).toBeDisabled()
    expect(screen.getByRole('radio', { name: /모던 블루/ })).toBeDisabled()
  })

  it('생성 실패는 확정 버튼을 누르기 전에도 표시한다', () => {
    const failedJob = {
      data: { status: 'FAILED', progress: 10, failureMessage: 'AI 응답 오류' },
      error: null,
      timedOut: false,
    } as unknown as ReturnType<typeof useGenerationJob>
    const retry = vi.fn()

    renderWithProviders(<TemplateChoicePanel job={failedJob} onRetry={retry} />)

    expect(screen.getByRole('alert')).toHaveTextContent('AI 응답 오류')
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(retry).toHaveBeenCalledOnce()
  })

  it('취소 요청 실패는 생성 상태와 무관하게 표시한다', () => {
    renderWithProviders(
      <TemplateChoicePanel
        job={idleJob}
        onRetry={() => undefined}
        cancelError={new Error('취소 요청 실패')}
      />,
    )

    expect(screen.getByRole('alert')).toHaveTextContent('알 수 없는 오류가 발생했습니다.')
  })

  it('생성이 완료되면 지연 없이 보고서로 이동한다', async () => {
    const completedJob = {
      data: { status: 'COMPLETED', progress: 100, reportId: 'report-1' },
      error: null,
      timedOut: false,
    } as unknown as ReturnType<typeof useGenerationJob>
    const onComplete = vi.fn()

    renderWithProviders(
      <Routes>
        <Route
          path="/"
          element={
            <TemplateChoicePanel
              job={completedJob}
              recovered
              onRetry={() => undefined}
              onComplete={onComplete}
            />
          }
        />
        <Route path="/reports/:reportId" element={<p>보고서 도착</p>} />
      </Routes>,
    )

    expect(await screen.findByText('보고서 도착')).toBeInTheDocument()
    expect(onComplete).toHaveBeenCalledOnce()
  })
})
