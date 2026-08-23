/**
 * AI 설정 API — 사용자 provider API Key 와 선택 가능한 모델 목록.
 *
 * API Key 원문은 서버로 보내는 순간에만 메모리에 있다. React Query 캐시·localStorage·
 * sessionStorage 어디에도 남기지 않으며, 저장 mutation 은 끝나면 변수(키)를 reset 한다.
 * 서버도 힌트(`****` + 끝 4자리)만 돌려주므로 화면에는 힌트만 보여준다.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch, apiFetchNoContent } from '../../lib/api/client'
import type {
  AiCredentialListResponse,
  AiCredentialResponse,
  AiModelCatalogResponse,
  AiProvider,
} from '../../lib/contracts/types'

export const settingsKeys = {
  credentials: ['settings', 'ai-credentials'] as const,
  models: ['ai', 'models'] as const,
}

/** 등록한 키 목록(힌트만). */
export function useAiCredentials() {
  return useQuery({
    queryKey: settingsKeys.credentials,
    queryFn: () => apiFetch<AiCredentialListResponse>('/api/me/ai-credentials'),
  })
}

/**
 * 선택 가능한 provider·모델 목록. `available` 은 키 등록 여부에 따라 바뀌므로
 * 키를 저장·삭제하면 함께 무효화한다.
 */
export function useAiModels(options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: settingsKeys.models,
    queryFn: () => apiFetch<AiModelCatalogResponse>('/api/ai/models'),
    enabled: options.enabled ?? true,
    staleTime: 60_000,
  })
}

export function useSaveAiCredential() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ provider, apiKey }: { provider: AiProvider; apiKey: string }) =>
      apiFetch<AiCredentialResponse>(`/api/me/ai-credentials/${provider}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ apiKey }),
      }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: settingsKeys.credentials })
      void client.invalidateQueries({ queryKey: settingsKeys.models })
    },
    // 키 원문이 mutation state(variables)에 남지 않도록 캐시 유지 시간을 0 으로 둔다.
    gcTime: 0,
  })
}

export function useDeleteAiCredential() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (provider: AiProvider) =>
      apiFetchNoContent(`/api/me/ai-credentials/${provider}`, { method: 'DELETE' }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: settingsKeys.credentials })
      void client.invalidateQueries({ queryKey: settingsKeys.models })
    },
  })
}
