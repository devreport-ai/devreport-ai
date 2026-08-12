/**
 * 보고서 생성 API — 요청과 상태 폴링.
 */
import { useCallback, useSyncExternalStore } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../lib/api/client'
import {
  isTerminalStatus,
  type GenerationJobResponse,
  type GenerationRequest,
  type JobIdResponse,
} from '../../lib/contracts/types'

/** 첫 폴링 간격. */
const POLL_START_MS = 2000

/** 폴링 간격 상한. 이 이상으로는 늘리지 않는다. */
const POLL_MAX_MS = 15_000

/**
 * 폴링 상한 — 응답 횟수로 센다.
 *
 * 무한 폴링을 금지하는 규칙이 있다. 서버가 작업을 끝내지도 실패시키지도 못하는
 * 상황에서 브라우저가 영원히 요청을 보내는 것을 막는다.
 *
 * 시각으로 재지 않는 이유: 서버가 준 `createdAt` 과 브라우저 시계를 빼면 두 시계가
 * 어긋난 만큼 오차가 난다. 사용자 PC 시계가 앞서 있으면 첫 응답부터 시간 초과가 된다.
 *
 * 아래 간격으로 60회면 약 14분이다.
 */
export const MAX_POLL_COUNT = 60

/**
 * 지수 백오프 간격.
 *
 * 고정 간격으로 계속 두드리면 오래 걸리는 작업일수록 서버에 낭비가 쌓인다.
 * 2초에서 시작해 1.5배씩 늘리고 15초에서 멈춘다 (2 → 3 → 4.5 → … → 15).
 *
 * 지수에서 1을 빼는 이유: 이 함수는 첫 응답을 받은 뒤에 처음 불린다. 그 시점의
 * `dataUpdateCount` 가 이미 1 이라 그대로 쓰면 첫 재조회가 2초가 아닌 3초가 된다.
 */
export function pollIntervalMs(dataUpdateCount: number): number {
  return Math.min(POLL_START_MS * 1.5 ** Math.max(0, dataUpdateCount - 1), POLL_MAX_MS)
}

/**
 * 다음 조회까지 기다릴 시간. 더 물어보지 않아야 하면 `false`.
 *
 * `refetchInterval` 콜백에서 떼어낸 이유는 이 판단이 틀리면 무한 폴링이나 조기 중단이
 * 되는데, 훅째로 돌리면 실제 타이머를 기다려야 해서 확인이 어렵기 때문이다.
 */
export function nextPollInterval(state: {
  error: unknown
  status?: GenerationJobResponse['status']
  dataUpdateCount: number
}): number | false {
  if (state.error) return false
  if (state.status && isTerminalStatus(state.status)) return false
  if (state.dataUpdateCount >= MAX_POLL_COUNT) return false
  return pollIntervalMs(state.dataUpdateCount)
}

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
  const query = useQuery({
    queryKey: generationKeys.job(jobId ?? ''),
    queryFn: () => apiFetch<GenerationJobResponse>(`/api/generations/${jobId}`),
    enabled: jobId !== null,
    refetchInterval: (query) =>
      nextPollInterval({
        error: query.state.error,
        status: query.state.data?.status,
        dataUpdateCount: query.state.dataUpdateCount,
      }),
  })

  const pollCount = usePollCount(jobId)

  /**
   * 상한에 걸려 폴링을 포기한 상태.
   *
   * 이 값을 밖으로 내보내지 않으면 화면은 마지막으로 받은 "진행 중" 을 그대로 붙들고
   * 있게 된다. 조회는 멈췄는데 사용자에게는 계속 진행 중으로 보이고, 생성 버튼도
   * 비활성인 채라 새로고침 말고는 빠져나갈 방법이 없다.
   */
  const timedOut =
    query.data !== undefined && !isTerminalStatus(query.data.status) && pollCount >= MAX_POLL_COUNT

  return { ...query, timedOut }
}

/**
 * 이 작업의 응답 수신 횟수를 구독한다.
 *
 * `refetchInterval` 콜백은 `query.state.dataUpdateCount` 를 받지만 `useQuery` 반환값에는
 * 그 값이 없다. 그래서 캐시를 직접 구독한다. 응답이 이전과 완전히 같으면 TanStack Query 가
 * 같은 객체를 돌려주어 다시 렌더되지 않으므로, `data` 만 보고 있으면 상한 도달을 놓친다.
 */
function usePollCount(jobId: string | null): number {
  const client = useQueryClient()
  const subscribe = useCallback(
    (onChange: () => void) => client.getQueryCache().subscribe(onChange),
    [client],
  )
  const getCount = useCallback(
    () =>
      jobId === null ? 0 : (client.getQueryState(generationKeys.job(jobId))?.dataUpdateCount ?? 0),
    [client, jobId],
  )
  return useSyncExternalStore(subscribe, getCount)
}
