/** 완료 신호 테스트 — 신호가 이르면 깨진 PDF, 안 켜지면 Chromium 타임아웃이다. */
import { afterEach, describe, expect, it } from 'vitest'
import { markReadyWhenLoaded, parseRenderToken } from './printReady'

afterEach(() => {
  delete document.documentElement.dataset.printState
  document.body.innerHTML = ''
})

describe('parseRenderToken', () => {
  it('백엔드가 붙이는 #token=… 형식을 읽는다', () => {
    // PdfReportRenderer.withRenderToken 이 만드는 형식 그대로
    expect(parseRenderToken('#token=abc123')).toBe('abc123')
    expect(parseRenderToken('#token=abc&extra=1')).toBe('abc')
  })

  it('토큰이 없으면 null 을 준다', () => {
    expect(parseRenderToken('')).toBeNull()
    expect(parseRenderToken('#')).toBeNull()
    expect(parseRenderToken('#token=')).toBeNull()
  })
})

describe('markReadyWhenLoaded', () => {
  it('이미지가 없으면 곧바로 신호를 켠다', async () => {
    await markReadyWhenLoaded(document.body)
    expect(document.documentElement.dataset.printState).toBe('ready')
  })

  it('이미지 로딩이 끝나야 신호를 켠다', async () => {
    const img = document.createElement('img')
    img.src = 'blob:fake'
    document.body.appendChild(img)

    let resolved = false
    const done = markReadyWhenLoaded(document.body).then(() => {
      resolved = true
    })
    await Promise.resolve()
    expect(resolved).toBe(false) // 아직 load 전

    img.dispatchEvent(new Event('load'))
    await done
    expect(document.documentElement.dataset.printState).toBe('ready')
  })

  it('깨진 이미지(error)여도 신호는 켠다 — 안 켜면 Chromium 이 매달린다', async () => {
    const img = document.createElement('img')
    img.src = 'blob:broken'
    document.body.appendChild(img)

    const done = markReadyWhenLoaded(document.body)
    await Promise.resolve() // fonts 대기가 끝나 리스너가 붙을 때까지 한 틱
    img.dispatchEvent(new Event('error'))
    await done
    expect(document.documentElement.dataset.printState).toBe('ready')
  })
})
