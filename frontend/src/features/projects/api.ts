/**
 * 프로젝트 API.
 *
 * HTTP 호출은 `lib/api` 를 거치고, 화면은 이 파일의 훅만 쓴다.
 * 화면 컴포넌트가 엔드포인트 경로를 알 필요가 없게 하려는 것이다.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../lib/api/client'
import type {
  ProjectIdResponse,
  ProjectPageResponse,
  ProjectRequest,
} from '../../lib/contracts/types'

/**
 * queryKey 를 한곳에 모은다.
 *
 * 문자열을 여기저기 흩뿌리면 무효화(invalidate)할 때 오타 하나로 조용히 실패한다.
 * 그러면 "만들었는데 목록에 안 뜬다" 가 되고 원인을 찾기 어렵다.
 */
export const projectKeys = {
  all: ['projects'] as const,
  list: (page: number, size: number) => ['projects', 'list', page, size] as const,
}

export function useProjects(page = 0, size = 20) {
  return useQuery({
    queryKey: projectKeys.list(page, size),
    queryFn: () => apiFetch<ProjectPageResponse>(`/api/projects?page=${page}&size=${size}`),
  })
}

export function useCreateProject() {
  const client = useQueryClient()

  return useMutation({
    mutationFn: (body: ProjectRequest) =>
      apiFetch<ProjectIdResponse>('/api/projects', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      }),
    // 생성 직후 목록을 다시 불러 새 프로젝트가 바로 보이게 한다.
    onSuccess: () => client.invalidateQueries({ queryKey: projectKeys.all }),
  })
}
