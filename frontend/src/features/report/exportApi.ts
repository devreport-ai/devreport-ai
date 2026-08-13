/**
 * PDF 내보내기 (#39) — 요청(202) → 상태 폴링 → 완료 시 다운로드.
 * 다운로드는 Authorization 이 필요해 <a href> 로 못 열고 blob 으로 받는다.
 */
import { useMutation, useQuery } from '@tanstack/react-query'
import { apiFetch } from '../../lib/api/client'
import { ApiError, toFallbackErrorResponse } from '../../lib/api/errors'
import { getAccessToken } from '../../lib/auth/tokenStore'
import { refreshSession } from '../../lib/auth/session'
import { MAX_POLL_COUNT, pollIntervalMs } from '../generation/api'
import type { ReportExportResponse } from '../../lib/contracts/types'

const EXPORT_TERMINAL = ['COMPLETED', 'FAILED', 'EXPIRED'] as const

export function isExportFinished(status: ReportExportResponse['status']): boolean {
  return (EXPORT_TERMINAL as readonly string[]).includes(status)
}

/** 생성 폴링과 같은 규칙: 종료 상태·오류·상한에서 멈춘다. */
export function nextExportPollInterval(state: {
  error: unknown
  status?: ReportExportResponse['status']
  dataUpdateCount: number
}): number | false {
  if (state.error) return false
  if (state.status && isExportFinished(state.status)) return false
  if (state.dataUpdateCount >= MAX_POLL_COUNT) return false
  return pollIntervalMs(state.dataUpdateCount)
}

export function useStartExport(reportId: string) {
  return useMutation({
    mutationFn: () =>
      apiFetch<{ exportId: string }>(`/api/reports/${reportId}/exports`, { method: 'POST' }),
  })
}

export function useExportStatus(exportId: string | null) {
  return useQuery({
    queryKey: ['exports', exportId ?? ''],
    queryFn: () => apiFetch<ReportExportResponse>(`/api/report-exports/${exportId}`),
    enabled: exportId !== null,
    refetchIntervalInBackground: true,
    refetchInterval: (query) =>
      nextExportPollInterval({
        error: query.state.error,
        status: query.state.data?.status,
        dataUpdateCount: query.state.dataUpdateCount,
      }),
  })
}

/** 완성된 PDF 를 받아 브라우저 다운로드를 건다. 401 이면 재발급 후 한 번 재시도. */
export async function downloadExportPdf(exportId: string, filename: string): Promise<void> {
  let response = await fetchPdf(exportId)
  if (response.status === 401 && (await refreshSession())) {
    response = await fetchPdf(exportId)
  }
  if (!response.ok) {
    throw new ApiError(
      response.status,
      toFallbackErrorResponse(`PDF 를 내려받지 못했습니다. (HTTP ${response.status})`),
    )
  }

  const url = URL.createObjectURL(await response.blob())
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    link.click()
  } finally {
    URL.revokeObjectURL(url)
  }
}

function fetchPdf(exportId: string): Promise<Response> {
  const token = getAccessToken()
  return fetch(`/api/report-exports/${exportId}/download`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
}
