/** 보고서 조회·저장 API. */
import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '../../lib/api/client'
import type { Report, ReportUpdateRequest } from '../../lib/contracts/types'

export const reportKeys = {
  detail: (reportId: string) => ['reports', reportId] as const,
}

export function useReport(reportId: string) {
  return useQuery({
    queryKey: reportKeys.detail(reportId),
    queryFn: () => apiFetch<Report>(`/api/reports/${reportId}`),
    // 편집 중 재조회가 일어나면 로컬 편집본을 덮어쓸 수 있다. 명시적으로만 다시 부른다.
    staleTime: Infinity,
    retry: false,
  })
}

export function saveReport(reportId: string, body: ReportUpdateRequest): Promise<Report> {
  return apiFetch<Report>(`/api/reports/${reportId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}
