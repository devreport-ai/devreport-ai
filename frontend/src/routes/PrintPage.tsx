/**
 * 출력 전용 보고서 페이지 (#39) — Backend Chromium 이 PDF 로 찍는 화면.
 *
 * 편집 UI·앱 chrome 없이 문서만 그린다. 렌더링은 편집기와 같은 BlockView·
 * 템플릿 CSS 를 쓰므로 미리보기와 레이아웃이 일치한다.
 * 데이터·폰트·이미지가 모두 준비된 뒤에만 완료 신호를 켠다. 실패 시에는 켜지
 * 않는다 — Chromium 이 타임아웃으로 실패해야 깨진 PDF 가 나가지 않는다.
 */
import { useEffect, useState } from 'react'
import { useLocation, useParams } from 'react-router'
import { fetchExportImage, useRenderData } from '../features/print/api'
import { markReadyWhenLoaded, parseRenderToken } from '../features/print/printReady'
import { BlockView } from '../features/report/BlockView'
import { FALLBACK_TEMPLATE, findTemplate } from '../features/report/templates'
import 'pretendard/dist/web/variable/pretendardvariable.css'
import '../features/report/report-document.css'

export default function PrintPage() {
  const { exportId = '' } = useParams()
  const location = useLocation()
  const token = parseRenderToken(location.hash)

  // 내보내기(또는 토큰)가 바뀌면 통째로 재마운트해 이전 이미지·준비 신호가 남지 않게 한다
  return <PrintContent key={`${exportId}:${token ?? ''}`} exportId={exportId} token={token} />
}

function PrintContent({ exportId, token }: { exportId: string; token: string | null }) {
  const data = useRenderData(exportId, token)
  const [imageUrls, setImageUrls] = useState<Record<string, string> | null>(null)

  // 스냅샷 이미지를 먼저 blob 으로 받아둔다. imageFileIds 가 비면 즉시 완료다.
  useEffect(() => {
    if (!data.data || token === null) return
    let cancelled = false
    const urls: Record<string, string> = {}

    Promise.all(
      data.data.imageFileIds.map(async (fileId) => {
        try {
          const url = await fetchExportImage(exportId, fileId, token)
          // 정리 이후 도착한 blob 은 아무도 해제하지 않으므로 즉시 해제한다
          if (cancelled) URL.revokeObjectURL(url)
          else urls[fileId] = url
        } catch {
          // 이미지 하나가 깨져도 문서 전체를 막지 않는다. 대체 텍스트로 나간다.
        }
      }),
    ).then(() => {
      if (!cancelled) setImageUrls(urls)
    })

    return () => {
      cancelled = true
      Object.values(urls).forEach((url) => URL.revokeObjectURL(url))
    }
  }, [data.data, exportId, token])

  // 문서와 이미지가 DOM 에 오른 뒤 폰트·이미지 로딩을 기다려 신호를 켠다.
  // 언마운트(다른 내보내기로 전환) 시 신호를 지워 이전 ready 가 재사용되지 않게 한다.
  useEffect(() => {
    if (!data.data || imageUrls === null) return
    void markReadyWhenLoaded()
    return () => {
      delete document.documentElement.dataset.printState
    }
  }, [data.data, imageUrls])

  if (token === null) {
    return <p className="p-6">출력 토큰이 없습니다.</p>
  }
  if (data.isPending || (data.data && imageUrls === null)) {
    return <p className="p-6">문서를 준비하는 중…</p>
  }
  if (data.error) {
    // 만료(404) 포함. 신호를 켜지 않으므로 Chromium 은 타임아웃으로 실패한다.
    return <p className="p-6">출력 데이터를 불러올 수 없습니다. (만료되었을 수 있습니다)</p>
  }

  const { document: doc, templateId } = data.data
  const template = findTemplate(templateId) ?? FALLBACK_TEMPLATE

  return (
    <div className={`report-sheet print-page print-page-v2 ${template.className}`}>
      <span className="print-page-v2__accent" aria-hidden />
      <header className="print-page-v2__header">
        <p>DEVREPORT AI</p>
        <h1>{doc.metadata.title}</h1>
        <MetaLine metadata={doc.metadata} />
      </header>
      {doc.sections.map((section) => (
        <section key={section.id}>
          <h2 className="print-page-v2__section-title">
            <i aria-hidden />
            {section.title}
          </h2>
          {section.blocks.map((block) => (
            <BlockView
              key={block.id}
              block={block}
              imageUrl={block.type === 'image' ? imageUrls?.[block.fileId] : undefined}
            />
          ))}
        </section>
      ))}
    </div>
  )
}

function MetaLine({ metadata }: { metadata: { author?: string; course?: string; date?: string } }) {
  const parts = [metadata.author, metadata.course, metadata.date].filter(Boolean)
  if (parts.length === 0) return null
  return <p className="rpt-meta">{parts.join(' · ')}</p>
}
