/**
 * 프로젝트 파일 API.
 *
 * 업로드는 진행률이 필요해 XHR 기반 `lib/api/upload.ts` 를 쓴다.
 * 목록·삭제는 일반 JSON 이라 `apiFetch` 를 쓴다.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch, apiFetchNoContent } from '../../lib/api/client'
import { uploadProjectFile } from '../../lib/api/upload'
import type { FilePageResponse } from '../../lib/contracts/types'

export const fileKeys = {
  all: (projectId: string) => ['projects', projectId, 'files'] as const,
}

export const allFileKeys = {
  all: (projectId: string) => [...fileKeys.all(projectId), 'all'] as const,
}

export function useProjectFiles(projectId: string) {
  return useQuery({
    queryKey: fileKeys.all(projectId),
    // 업로드가 끝날 때마다 이 목록을 다시 부른다. size 는 계약 최대치인 100 을 쓴다.
    queryFn: () => apiFetch<FilePageResponse>(`/api/projects/${projectId}/files?page=0&size=100`),
  })
}

/** 프로젝트의 모든 파일을 모아 이미지 블록 교체 선택지로 제공한다. */
export function useAllProjectFiles(projectId: string) {
  return useQuery({
    queryKey: allFileKeys.all(projectId),
    queryFn: () => fetchAllProjectFiles(projectId),
  })
}

/** 첫 페이지의 totalPages를 기준으로 나머지 파일 페이지를 함께 조회한다. */
export async function fetchAllProjectFiles(projectId: string): Promise<FilePageResponse> {
  const first = await apiFetch<FilePageResponse>(`/api/projects/${projectId}/files?page=0&size=100`)
  if (!Number.isFinite(first.totalPages) || first.totalPages <= 1) return first

  const pages = await Promise.all(
    Array.from({ length: first.totalPages - 1 }, (_, index) =>
      apiFetch<FilePageResponse>(`/api/projects/${projectId}/files?page=${index + 1}&size=100`),
    ),
  )
  const items = [first, ...pages].flatMap((page) => page.items ?? [])
  return { ...first, items, page: 0, size: items.length, totalPages: 1 }
}

export function useDeleteFile(projectId: string) {
  const client = useQueryClient()

  return useMutation({
    mutationFn: (fileId: string) =>
      apiFetchNoContent(`/api/projects/${projectId}/files/${fileId}`, { method: 'DELETE' }),
    onSuccess: () => client.invalidateQueries({ queryKey: fileKeys.all(projectId) }),
  })
}

export { uploadProjectFile }
