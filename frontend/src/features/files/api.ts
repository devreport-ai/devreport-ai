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

export function useProjectFiles(projectId: string) {
  return useQuery({
    queryKey: fileKeys.all(projectId),
    // 업로드가 끝날 때마다 이 목록을 다시 부른다. size 는 계약 최대치인 100 을 쓴다.
    queryFn: () => apiFetch<FilePageResponse>(`/api/projects/${projectId}/files?page=0&size=100`),
  })
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
