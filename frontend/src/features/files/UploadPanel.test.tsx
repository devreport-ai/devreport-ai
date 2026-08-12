/**
 * 업로드 패널 테스트.
 *
 * 이슈 #36 의 "업로드 진행률·성공·실패 표시" 와 완료 조건의 "실패를 확인하고 재시도 가능" 을
 * 고정한다. 업로드 함수는 XHR 기반이라 jsdom 에서 그대로 돌리기 어려우므로 모듈을 가로챈다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import { UploadPanel } from './UploadPanel'
import { renderWithProviders } from '../../test/renderWithProviders'
import { ApiError } from '../../lib/api/errors'

const uploadMock = vi.fn()

vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api')>()
  return { ...actual, uploadProjectFile: (...args: unknown[]) => uploadMock(...args) }
})

/** 파일 선택창을 거치지 않고 input 에 파일을 직접 넣는다. */
function selectFiles(files: File[]) {
  const input = document.getElementById('file-input') as HTMLInputElement
  Object.defineProperty(input, 'files', { value: files, configurable: true })
  input.dispatchEvent(new Event('change', { bubbles: true }))
}

beforeEach(() => {
  uploadMock.mockReset()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('UploadPanel', () => {
  it('AI 입력 형식 제한을 화면에 안내한다', () => {
    // 안내가 없으면 사용자는 "PDF 올렸는데 왜 반영이 안 되지" 를 겪는다.
    renderWithProviders(<UploadPanel projectId="p1" />)

    expect(screen.getByText(/PDF 와 DOCX 는 보관만 됩니다/)).toBeInTheDocument()
    expect(screen.getByText(/\.env/)).toBeInTheDocument()
  })

  it('업로드 중 진행률을 보여주고 끝나면 완료로 바꾼다', async () => {
    // 업로드가 언제 끝날지 테스트가 정해야 진행률 표시를 확인할 틈이 생긴다.
    // 그냥 resolve 하면 완료 상태로 건너뛰어 42% 를 못 본다.
    let finish: ((value: { fileId: string }) => void) | undefined
    let reportProgress: ((ratio: number) => void) | undefined

    uploadMock.mockImplementation(
      (_projectId: string, _file: File, options: { onProgress?: (r: number) => void }) => {
        reportProgress = options.onProgress
        return new Promise<{ fileId: string }>((resolve) => {
          finish = resolve
        })
      },
    )

    renderWithProviders(<UploadPanel projectId="p1" />)
    selectFiles([new File(['x'], 'notes.txt', { type: 'text/plain' })])

    await waitFor(() => expect(reportProgress).toBeDefined())
    reportProgress?.(0.42)
    expect(await screen.findByText('42%')).toBeInTheDocument()

    finish?.({ fileId: 'f1' })
    expect(await screen.findByText('완료')).toBeInTheDocument()
  })

  it('여러 파일을 고르면 병렬로 업로드하고 완료 개수를 센다', async () => {
    uploadMock.mockResolvedValue({ fileId: 'f' })

    renderWithProviders(<UploadPanel projectId="p1" />)
    selectFiles([
      new File(['a'], 'a.txt', { type: 'text/plain' }),
      new File(['b'], 'b.md', { type: 'text/markdown' }),
    ])

    expect(await screen.findByText('2 / 2 완료')).toBeInTheDocument()
    expect(uploadMock).toHaveBeenCalledTimes(2)
  })

  it('실패한 파일만 다시 시도할 수 있다', async () => {
    uploadMock.mockRejectedValueOnce(
      new ApiError(413, {
        code: 'FILE_TOO_LARGE',
        message: '파일이 너무 큽니다.',
        details: null,
        timestamp: '2026-08-12T00:00:00+09:00',
      }),
    )

    renderWithProviders(<UploadPanel projectId="p1" />)
    selectFiles([new File(['a'], 'big.zip', { type: 'application/zip' })])

    // 서버가 준 message 를 그대로 보여준다. 프론트가 문구를 새로 만들지 않는다.
    expect(await screen.findByText('파일이 너무 큽니다.')).toBeInTheDocument()

    uploadMock.mockResolvedValueOnce({ fileId: 'f1' })
    ;(await screen.findByRole('button', { name: '다시 시도' })).click()

    expect(await screen.findByText('완료')).toBeInTheDocument()
  })

  it('20 MiB 를 넘는 파일은 서버에 보내지 않고 미리 막는다', async () => {
    renderWithProviders(<UploadPanel projectId="p1" />)

    const big = new File(['x'], 'huge.zip', { type: 'application/zip' })
    Object.defineProperty(big, 'size', { value: 21 * 1024 * 1024 })
    selectFiles([big])

    expect(await screen.findByText(/20 MiB 를 넘는 파일/)).toBeInTheDocument()
    expect(uploadMock).not.toHaveBeenCalled()
  })
})
