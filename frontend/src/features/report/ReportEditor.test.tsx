/** 편집기 통합 동작 — #38 완료 조건(템플릿 전환 시 콘텐츠 유지)과 편집·삭제·undo 를 고정한다. */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, screen } from '@testing-library/react'
import { ReportEditor } from './ReportEditor'
import { renderWithProviders } from '../../test/renderWithProviders'
import type { Report } from '../../lib/contracts/types'

function report(): Report {
  return {
    id: 'r-1',
    projectId: 'p-1',
    document: {
      metadata: { title: '실습 보고서', author: '작성자' },
      sections: [
        {
          id: 'sec-1',
          title: '개요',
          blocks: [
            { id: 'p-1', type: 'paragraph', content: '첫 문단입니다.' },
            { id: 'c-1', type: 'code', language: 'java', code: 'class A {}' },
          ],
        },
      ],
    },
    templateId: null,
    templateVersion: null,
    presentationSettings: {},
    version: 0,
    updatedAt: '2026-08-12T00:00:00+09:00',
  }
}

beforeEach(() => {
  // 자동 저장(PUT)이 나가도 테스트가 죽지 않게 성공 응답을 준다
  vi.stubGlobal(
    'fetch',
    vi.fn(() =>
      Promise.resolve(
        new Response(JSON.stringify({ ...report(), version: 1 }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    ),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('ReportEditor', () => {
  it('저장된 템플릿이 없으면 기본 템플릿을 저장한다', async () => {
    // 저장 안 하면 화면엔 기본 템플릿이 보이는데 서버는 null 이라 PDF 가 409 로 막힌다 (#130)
    vi.useFakeTimers()
    try {
      renderWithProviders(<ReportEditor report={report()} />)
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000)
      })
      const put = (fetch as ReturnType<typeof vi.fn>).mock.calls.find(
        ([, init]) => (init as RequestInit | undefined)?.method === 'PUT',
      )
      expect(put).toBeDefined()
      expect(String((put![1] as RequestInit).body)).toContain('"templateId":"default"')
    } finally {
      vi.useRealTimers()
    }
  })

  it('등록되지 않은 템플릿 id 는 기본값으로 덮어쓰지 않는다', async () => {
    // 목록에서 빠진 템플릿이라도 사용자가 고른 값이므로 자동 저장이 바꾸면 안 된다
    vi.useFakeTimers()
    try {
      const saved = { ...report(), templateId: 'unknown-template', templateVersion: 9 }
      renderWithProviders(<ReportEditor report={saved} />)
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000)
      })

      const put = (fetch as ReturnType<typeof vi.fn>).mock.calls.find(
        ([, init]) => (init as RequestInit | undefined)?.method === 'PUT',
      )
      expect(put).toBeUndefined()
      // 선택 상자가 빈 칸이 되지 않아야 한다 (리뷰 지적)
      const select = screen.getByLabelText('템플릿') as HTMLSelectElement
      expect(select.value).toBe('unknown-template')
      expect(screen.getByRole('option', { name: '알 수 없는 템플릿' })).toBeDisabled()
    } finally {
      vi.useRealTimers()
    }
  })

  it('좁은 화면용 편집·미리보기 탭을 제공한다', () => {
    const { container } = renderWithProviders(<ReportEditor report={report()} />)

    const tabs = screen.getAllByRole('tab')
    expect(tabs.map((t) => t.textContent)).toEqual(['콘텐츠', '미리보기'])
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true')

    fireEvent.click(tabs[1])
    expect(screen.getAllByRole('tab')[1]).toHaveAttribute('aria-selected', 'true')
    // 좁은 화면에서 어느 패널을 보여줄지는 이 클래스가 정한다 (CSS 미디어쿼리와 짝)
    expect(container.querySelector('.editor-workspace--preview')).not.toBeNull()
    expect(container.querySelector('.editor-workspace--edit')).toBeNull()
  })

  it('툴바 로고로 홈에 갈 수 있다', () => {
    renderWithProviders(<ReportEditor report={report()} />)

    expect(screen.getByRole('link', { name: 'DevReport AI 홈' })).toHaveAttribute('href', '/')
  })

  it('문서 제목·메타·블록을 렌더링한다', () => {
    renderWithProviders(<ReportEditor report={report()} />)

    expect(screen.getByRole('heading', { name: '실습 보고서' })).toBeInTheDocument()
    expect(screen.getByText('작성자')).toBeInTheDocument()
    expect(screen.getByText('첫 문단입니다.')).toBeInTheDocument()
    expect(screen.getByText('class A {}')).toBeInTheDocument()
  })

  it('템플릿을 바꿔도 콘텐츠가 사라지지 않는다', () => {
    // #38 완료 조건. 계약: 템플릿 변경은 document 를 수정하지 않는다.
    const { container } = renderWithProviders(<ReportEditor report={report()} />)

    fireEvent.change(screen.getByLabelText('템플릿'), { target: { value: 'compact' } })

    expect(container.querySelector('.tpl-compact')).not.toBeNull()
    expect(container.querySelector('.rpt-stat-strip')).not.toBeNull()
    expect(screen.getByText('첫 문단입니다.')).toBeInTheDocument()
    expect(screen.getByText('class A {}')).toBeInTheDocument()
  })

  it('블록 입력을 확인 없이 미리보기에 즉시 반영한다', () => {
    renderWithProviders(<ReportEditor report={report()} />)

    fireEvent.click(screen.getAllByRole('button', { name: '이 블록 편집' })[0])
    fireEvent.change(screen.getByLabelText('내용'), { target: { value: '고친 문단' } })

    expect(screen.getAllByText('고친 문단')).toHaveLength(2)
    expect(screen.queryByText('첫 문단입니다.')).toBeNull()
  })

  it('블록을 삭제하고 실행 취소로 되살린다', () => {
    renderWithProviders(<ReportEditor report={report()} />)

    fireEvent.click(screen.getAllByRole('button', { name: '블록 삭제' })[0])
    expect(screen.queryByText('첫 문단입니다.')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: '실행 취소' }))
    expect(screen.getByText('첫 문단입니다.')).toBeInTheDocument()
  })

  it('블록을 추가할 수 있다', () => {
    renderWithProviders(<ReportEditor report={report()} />)

    expect(screen.getAllByRole('button', { name: '블록 삭제' })).toHaveLength(2)

    fireEvent.click(screen.getByRole('button', { name: '+ 블록 추가' }))
    fireEvent.click(screen.getByRole('button', { name: '콜아웃' }))

    expect(screen.getAllByRole('button', { name: '블록 삭제' })).toHaveLength(3)
  })

  it('문서 정보와 섹션 제목을 편집할 수 있다', () => {
    renderWithProviders(<ReportEditor report={report()} />)

    fireEvent.click(screen.getByRole('button', { name: '문서 정보 편집' }))
    const title = screen.getByDisplayValue('실습 보고서')
    fireEvent.change(title, { target: { value: '최종 보고서' } })
    expect(screen.getByRole('heading', { name: '최종 보고서' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '편집 종료' }))

    fireEvent.click(screen.getByRole('button', { name: '개요 섹션 제목 편집' }))
    fireEvent.change(screen.getByRole('textbox', { name: '섹션 제목' }), {
      target: { value: '새 개요' },
    })
    expect(screen.getByText('새 개요')).toBeInTheDocument()
  })

  it('빈 필수값은 미리보기에 반영하되 저장과 편집 종료를 막는다', async () => {
    vi.useFakeTimers()
    try {
      renderWithProviders(<ReportEditor report={report()} />)

      fireEvent.click(screen.getByRole('button', { name: '문서 정보 편집' }))
      const title = screen.getByDisplayValue('실습 보고서')
      fireEvent.change(title, { target: { value: '' } })

      expect(title).toHaveValue('')
      expect(screen.getByText('필수값 확인')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: '편집 종료' })).toBeDisabled()

      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000)
      })
      expect(
        (fetch as ReturnType<typeof vi.fn>).mock.calls.some(
          ([, init]) => (init as RequestInit | undefined)?.method === 'PUT',
        ),
      ).toBe(false)

      fireEvent.change(title, { target: { value: '새 보고서' } })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000)
      })
      expect(
        (fetch as ReturnType<typeof vi.fn>).mock.calls.some(
          ([, init]) => (init as RequestInit | undefined)?.method === 'PUT',
        ),
      ).toBe(true)
    } finally {
      vi.useRealTimers()
    }
  })

  it('사이드 패널 템플릿은 실제 문서 레일 구조를 렌더링한다', () => {
    const { container } = renderWithProviders(<ReportEditor report={report()} />)

    fireEvent.change(screen.getByLabelText('템플릿'), { target: { value: 'side-panel' } })

    expect(container.querySelector('.tpl-side-panel .rpt-side-rail')).not.toBeNull()
  })

  it('생성 흐름에서 고른 템플릿은 편집 없이도 자동 저장된다', async () => {
    // 저장 기준을 입력값으로 잡으면 "이미 저장됨" 취급되어 PUT 이 안 나가고,
    // 서버 templateId 가 null 인 채라 PDF 내보내기가 409 로 막힌다 (실제 있었던 버그)
    vi.useFakeTimers()
    try {
      renderWithProviders(<ReportEditor report={report()} initialTemplateId="compact" />)

      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000)
      })

      const put = (fetch as ReturnType<typeof vi.fn>).mock.calls.find(
        ([, init]) => (init as RequestInit | undefined)?.method === 'PUT',
      )
      expect(put).toBeDefined()
      expect(String((put![1] as RequestInit).body)).toContain('"templateId":"compact"')
      expect(String((put![1] as RequestInit).body)).toContain('"templateVersion":1')
    } finally {
      vi.useRealTimers()
    }
  })

  it('생성 흐름에서 고른 템플릿이 저장된 값이 없을 때 적용된다', () => {
    const { container } = renderWithProviders(
      <ReportEditor report={report()} initialTemplateId="compact" />,
    )
    expect(container.querySelector('.tpl-compact')).not.toBeNull()
  })

  it('이미 저장된 템플릿이 있으면 생성 흐름 선택값을 무시한다', () => {
    // 새로고침 시 저장값이 진실이다. 켜켜이 덮어쓰면 사용자가 편집기에서 바꾼 게 사라진다.
    const saved: Report = { ...report(), templateId: 'default', templateVersion: 1 }
    const { container } = renderWithProviders(
      <ReportEditor report={saved} initialTemplateId="compact" />,
    )
    expect(container.querySelector('.tpl-default')).not.toBeNull()
  })

  it('이미 저장된 템플릿 버전을 자동으로 덮어쓰지 않는다', async () => {
    vi.useFakeTimers()
    try {
      const saved: Report = { ...report(), templateId: 'default', templateVersion: 1 }
      renderWithProviders(<ReportEditor report={saved} />)

      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000)
      })

      expect(
        (fetch as ReturnType<typeof vi.fn>).mock.calls.some(
          ([, init]) => (init as RequestInit | undefined)?.method === 'PUT',
        ),
      ).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })
})
