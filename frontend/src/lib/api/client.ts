/**
 * 이 앱의 유일한 HTTP 출입구.
 *
 * 규칙: 컴포넌트에서 `fetch` 를 직접 부르지 않고 반드시 이 파일을 거친다.
 * 그래야 base URL, 타임아웃, 에러 응답 해석 같은 공통 처리를 한 곳에서만
 * 고칠 수 있다. 화면마다 fetch 를 흩뿌리면 나중에 인증 토큰을 붙일 때
 * 수십 군데를 찾아다녀야 한다.
 *
 * 이번 범위에서 일부러 넣지 않은 것 (이슈 #50 참고):
 *  - Authorization 헤더 주입 / 401 재발급 → 로그인 화면이 없어 검증할 수 없다.
 *    회원가입·로그인 이슈에서 이 파일에 추가한다.
 *  - 계약 기반 타입 생성 → 계약이 아직 움직이고 있어 후속 이슈로 미뤘다.
 */
import { ApiError, isErrorResponse, toFallbackErrorResponse, type ErrorResponse } from './errors'

/**
 * 개발 중에는 빈 문자열이다.
 *
 * 빈 문자열이면 `/api/...` 로 요청이 나가고, vite.config.ts 의 프록시가
 * 그것을 localhost:8080 으로 넘긴다. 브라우저는 같은 오리진으로 인식하므로
 * CORS 가 발생하지 않는다. 배포 환경에서는 실제 Backend 주소가 들어온다.
 */
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

/**
 * 응답이 없어도 무한정 기다리지 않기 위한 상한.
 *
 * 보고서 생성처럼 오래 걸리는 작업은 요청 자체가 길어지는 게 아니라
 * 202 로 즉시 응답받고 폴링하는 구조라, 개별 요청은 이 정도면 충분하다.
 * 더 필요한 호출은 options.timeoutMs 로 개별 조정한다.
 */
const DEFAULT_TIMEOUT_MS = 10_000

export interface ApiRequestOptions extends RequestInit {
  /** 이 요청에만 적용할 타임아웃(밀리초). */
  timeoutMs?: number
}

/**
 * Backend 를 호출하고 JSON 을 파싱해 돌려준다.
 *
 * 실패하면 항상 {@link ApiError} 를 던진다. 성공/실패를 반환값으로 구분하지 않고
 * 예외로 처리하는 이유는, 호출부가 실패를 조용히 무시하는 실수를 막기 위해서다.
 *
 * @param path `/api/projects` 처럼 슬래시로 시작하는 경로
 */
export async function apiFetch<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const { timeoutMs = DEFAULT_TIMEOUT_MS, headers, signal, ...rest } = options

  // 타임아웃과 호출부가 넘긴 취소 신호를 함께 걸어둔다.
  // 화면을 벗어날 때 호출부가 요청을 끊을 수 있어야 폴링이 새지 않는다.
  const timeoutSignal = AbortSignal.timeout(timeoutMs)
  const mergedSignal = signal ? AbortSignal.any([signal, timeoutSignal]) : timeoutSignal

  let response: Response
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      ...rest,
      signal: mergedSignal,
      headers: {
        Accept: 'application/json',
        ...headers,
      },
    })
  } catch (cause) {
    // 여기로 오는 건 서버가 응답을 못 준 경우다(네트워크 끊김, 타임아웃, 취소).
    // 서버가 거절한 것과는 성격이 다르므로 상태코드를 0 으로 표시해 구분한다.
    const message =
      cause instanceof DOMException && cause.name === 'TimeoutError'
        ? '서버 응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.'
        : '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.'
    throw new ApiError(0, toFallbackErrorResponse(message))
  }

  if (!response.ok) {
    throw new ApiError(response.status, await readErrorBody(response))
  }

  // 204 No Content 는 본문이 없다. 삭제 API 등이 여기에 해당한다.
  // 그대로 response.json() 을 부르면 파싱 에러가 난다.
  if (response.status === 204) {
    return undefined as T
  }

  return (await response.json()) as T
}

/**
 * 실패 응답의 본문을 ErrorResponse 로 해석한다.
 *
 * 규격을 벗어난 응답(HTML 오류 페이지 등)이 와도 화면이 깨지지 않도록
 * 항상 ErrorResponse 모양으로 맞춰서 돌려준다.
 */
async function readErrorBody(response: Response): Promise<ErrorResponse> {
  try {
    const body: unknown = await response.json()
    if (isErrorResponse(body)) return body
  } catch {
    // JSON 이 아니었다는 뜻이다. 아래 기본 메시지로 넘어간다.
  }
  return toFallbackErrorResponse(`요청을 처리하지 못했습니다. (HTTP ${response.status})`)
}
