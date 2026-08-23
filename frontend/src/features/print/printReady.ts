/**
 * Backend Chromium 이 기다리는 완료 신호 (#39).
 * PdfReportRenderer 는 `html[data-print-state="ready"]` 셀렉터가 나타날 때까지 대기한다.
 * 폰트·이미지가 로딩되기 전에 표시하면 PDF 에 빈 글꼴·빈 이미지가 박힌다.
 */

/** URL fragment(#token=…)에서 render token 을 꺼낸다. 백엔드가 이 형식으로 전달한다. */
export function parseRenderToken(hash: string): string | null {
  const params = new URLSearchParams(hash.replace(/^#/, ''))
  const token = params.get('token')
  return token === null || token === '' ? null : token
}

/** 폰트와 문서 안 모든 이미지의 로딩이 끝나면 완료 신호를 켠다. */
export async function markReadyWhenLoaded(root: HTMLElement = document.documentElement) {
  // jsdom 등 fonts API 가 없는 환경에서는 폰트 대기를 건너뛴다
  await document.fonts?.ready
  await Promise.all(
    Array.from(root.querySelectorAll('img')).map((img) =>
      img.complete
        ? Promise.resolve()
        : new Promise<void>((resolve) => {
            // 깨진 이미지도 신호는 켜야 한다. 안 켜면 Chromium 이 타임아웃까지 매달린다.
            img.addEventListener('load', () => resolve(), { once: true })
            img.addEventListener('error', () => resolve(), { once: true })
          }),
    ),
  )
  document.documentElement.dataset.printState = 'ready'
}
