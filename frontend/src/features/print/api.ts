/**
 * 출력 전용 데이터 조회 (#39). 인증은 Bearer 가 아니라 단기 render token 이다.
 * 만료·무효 토큰은 404 — 계약상 200/404 두 가지뿐이다.
 */
import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '../../lib/api/client'
import type { ReportRenderData } from '../../lib/contracts/types'

export function useRenderData(exportId: string, token: string | null) {
  return useQuery({
    queryKey: ['print', exportId, token],
    queryFn: () =>
      apiFetch<ReportRenderData>(`/api/report-exports/${exportId}/render-data`, {
        headers: { 'X-Render-Token': token ?? '' },
      }),
    enabled: token !== null,
    retry: false, // 404(만료)는 다시 물어도 404 다. Chromium 타임아웃 전에 빨리 실패한다
    staleTime: Infinity,
  })
}

/** 스냅샷 이미지를 blob URL 로 받는다. <img src> 에 헤더를 실을 수 없어서다. */
export async function fetchExportImage(
  exportId: string,
  fileId: string,
  token: string,
): Promise<string> {
  const response = await fetch(`/api/report-exports/${exportId}/files/${fileId}`, {
    headers: { 'X-Render-Token': token },
  })
  if (!response.ok) throw new Error(`image ${fileId}: HTTP ${response.status}`)
  return URL.createObjectURL(await response.blob())
}
