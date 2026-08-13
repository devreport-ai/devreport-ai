/**
 * 문서 안 이미지 블록들의 파일을 blob URL 로 받아둔다 (#38).
 * 파일 API 가 인증 필수라 <img src> 직접 지정이 안 된다.
 */
import { useEffect, useMemo, useState } from 'react'
import { fetchAuthedBlobUrl } from '../../lib/api/blob'
import type { ReportDocument } from '../../lib/contracts/types'

export function useImageUrls(projectId: string, document: ReportDocument): Record<string, string> {
  const [urls, setUrls] = useState<Record<string, string>>({})

  // 편집으로 문서가 바뀌어도 이미지 fileId 목록이 같으면 다시 받지 않는다
  const fileIdsKey = useMemo(
    () =>
      document.sections
        .flatMap((s) => s.blocks)
        .filter((b) => b.type === 'image')
        .map((b) => b.fileId)
        .sort()
        .join(','),
    [document],
  )

  useEffect(() => {
    if (fileIdsKey === '') return
    let cancelled = false
    const loaded: Record<string, string> = {}

    Promise.all(
      fileIdsKey.split(',').map(async (fileId) => {
        try {
          loaded[fileId] = await fetchAuthedBlobUrl(`/api/projects/${projectId}/files/${fileId}`)
        } catch {
          // 한 장이 깨져도 나머지는 보여준다. 실패한 것은 대체 텍스트로 남는다.
        }
      }),
    ).then(() => {
      if (!cancelled) setUrls(loaded)
    })

    return () => {
      cancelled = true
      Object.values(loaded).forEach((url) => URL.revokeObjectURL(url))
    }
  }, [projectId, fileIdsKey])

  return urls
}
