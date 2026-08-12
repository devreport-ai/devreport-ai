/**
 * 보고서 생성 API — 요청과 상태 폴링.
 */
import { useMutation, useQuery } from '@tanstack/react-query'
import { apiFetch } from '../../lib/api/client'
import {
  isTerminalStatus,
  type GenerationJobResponse,
  type GenerationRequest,
  type JobIdResponse,
} from '../../lib/contracts/types'

/** 폴링 간격. 계약 문서가 2~3초를 권한다. */
const POLL_INTERVAL_MS = 2500

/**
 * 폴링 상한 — 응답 횟수로 센다.
 *
 * 무한 폴링을 금지하는 규칙이 있다. 서버가 작업을 끝내지도 실패시키지도 못하는
 * 상황에서 브라우저가 영원히 요청을 보내는 것을 막는다.
 *
 * 시각(`Date.now()`)이 아니라 횟수로 세는 이유: 렌더 중에 시각을 읽거나 ref 를 만지면
 * React 순수성 규칙을 어긴다. `dataUpdateCount` 는 TanStack Query 가 관리하는 값이라
 * 렌더에 부수효과를 만들지 않는다.
 *
 * 2.5초 × 240 = 약 10분.
 */
const MAX_POLL_COUNT = 240

export const generationKeys = {
  job: (jobId: string) => ['generations', jobId] as const,
}

export function useStartGeneration(projectId: string) {
  return useMutation({
    mutationFn: (body: GenerationRequest) =>
      apiFetch<JobIdResponse>(`/api/projects/${projectId}/generations`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      }),
  })
}

/**
 * 생성 작업 상태를 폴링한다.
 *
 * `refetchInterval` 에 함수를 주면 매 응답마다 다시 계산된다. 끝난 작업에는
 * `false` 를 돌려 폴링을 멈춘다. 직접 `setInterval` 을 쓰면 이 정리를 손으로 해야 하고
 * StrictMode 에서 타이머가 두 개 도는 함정이 있다.
 *
 * @param jobId 없으면(아직 생성 요청 전) 폴링하지 않는다.
 */
export function useGenerationJob(jobId: string | null) {
  return useQuery({
    queryKey: generationKeys.job(jobId ?? ''),
    queryFn: () => apiFetch<GenerationJobResponse>(`/api/generations/${jobId}`),
    enabled: jobId !== null,
    refetchInterval: (query) => {
      const status = query.state.data?.status
      if (status && isTerminalStatus(status)) return false
      // 상한을 넘기면 멈춘다. 화면은 마지막으로 받은 상태를 그대로 보여준다.
      if (query.state.dataUpdateCount >= MAX_POLL_COUNT) return false
      return POLL_INTERVAL_MS
    },
  })
}
