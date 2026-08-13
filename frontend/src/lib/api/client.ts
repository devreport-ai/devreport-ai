/**
 * 이 앱의 유일한 HTTP 출입구.
 *
 * 규칙: 컴포넌트에서 `fetch` 를 직접 부르지 않고 반드시 이 파일을 거친다.
 * 그래야 base URL, 타임아웃, 에러 응답 해석 같은 공통 처리를 한 곳에서만
 * 고칠 수 있다. 화면마다 fetch 를 흩뿌리면 나중에 인증 토큰을 붙일 때
 * 수십 군데를 찾아다녀야 한다.
 *
 * 본문이 있는 응답과 없는 응답을 두 함수로 나눠 둔 이유는 아래 apiFetch 주석 참고.
 *
 * 인증 처리(이슈 #61 에서 추가):
 *  - 토큰이 있으면 모든 요청에 Authorization: Bearer 를 자동으로 붙인다.
 *  - 401 을 받으면 Refresh Token 으로 한 번만 재발급하고 원 요청을 다시 보낸다.
 *    재발급까지 실패하면 원래의 401 을 그대로 던진다 — 화면은 로그인으로 보낸다.
 */
import { ApiError, isErrorResponse, toFallbackErrorResponse, type ErrorResponse } from './errors'
import { getAccessToken } from '../auth/tokenStore'
import { refreshSession } from '../auth/session'

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
 * 본문이 있는 응답을 기대하는 호출에 쓴다. (GET, POST 대부분)
 *
 * 반환 타입이 `Promise<T>` 라고 선언한 이상 실제로도 항상 T 를 줘야 한다.
 * 그래서 본문이 없는 204 가 오면 조용히 undefined 를 돌려주지 않고 에러로 만든다.
 * 예전 구현은 `undefined as T` 로 타입을 속였고, 호출부가 아무 검사 없이
 * 속성에 접근하다 런타임에 터질 수 있었다.
 *
 * 204 를 정상으로 기대하는 호출(DELETE, logout 등)은 {@link apiFetchNoContent} 를 쓴다.
 *
 * @param path `/api/projects` 처럼 슬래시로 시작하는 경로
 * @throws {ApiError} 서버가 거절했거나, 연결에 실패했거나, 본문이 없을 때
 * @throws {DOMException} 호출자가 signal 로 요청을 취소했을 때 (AbortError)
 */
export async function apiFetch<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const response = await request(path, options)

  if (response.status === 204) {
    throw new ApiError(
      response.status,
      toFallbackErrorResponse('서버가 본문 없이 응답했습니다. (204)'),
    )
  }

  // 성공 응답이라도 본문이 JSON 이 아닐 수 있다(프록시가 끼어든 경우 등).
  // 그대로 두면 날것의 SyntaxError 가 호출부로 새어 나가, 실패 처리 방식이
  // ApiError 와 두 갈래가 된다. 여기서 ApiError 로 통일한다.
  try {
    return (await response.json()) as T
  } catch (cause) {
    // 본문을 읽는 도중에도 취소·타임아웃이 날 수 있다. 그것까지 "해석 실패" 로
    // 뭉뚱그리면 원인을 잃는다. 아래에서 원인별로 먼저 걸러낸다.
    rethrowIfAbortOrTimeout(cause)
    throw new ApiError(response.status, toFallbackErrorResponse('서버 응답을 해석하지 못했습니다.'))
  }
}

/**
 * 본문이 없는 204 응답을 기대하는 호출에 쓴다.
 *
 * 계약상 `DELETE /api/projects/{id}`, `POST /api/auth/logout` 등이 여기에 해당한다.
 * 본문이 없으므로 돌려줄 값도 없다.
 *
 * 204 가 아닌 2xx 도 조용히 성공으로 넘기지 않는다. Backend 가 실수로 200 과 본문을
 * 돌려주면 계약이 어긋난 것이고, 그대로 통과시키면 아무도 눈치채지 못한 채
 * 응답 본문만 버려진다. 계약 위반은 그 자리에서 드러나야 한다.
 */
export async function apiFetchNoContent(
  path: string,
  options: ApiRequestOptions = {},
): Promise<void> {
  const response = await request(path, options)

  if (response.status !== 204) {
    throw new ApiError(
      response.status,
      toFallbackErrorResponse(`본문 없는 응답(204)을 기대했으나 ${response.status} 을 받았습니다.`),
    )
  }
}

/**
 * 실제 요청을 보내고, 실패면 {@link ApiError} 를 던진다.
 *
 * 성공/실패를 반환값으로 구분하지 않고 예외로 처리하는 이유는,
 * 호출부가 실패를 조용히 무시하는 실수를 막기 위해서다.
 */
async function request(path: string, options: ApiRequestOptions): Promise<Response> {
  const { timeoutMs = DEFAULT_TIMEOUT_MS, headers, signal, ...rest } = options

  const send = async (): Promise<Response> => {
    // 타임아웃과 호출부가 넘긴 취소 신호를 함께 걸어둔다.
    // 화면을 벗어날 때 호출부가 요청을 끊을 수 있어야 폴링이 새지 않는다.
    // 재시도마다 새로 만든다 — 재발급에 시간을 썼어도 재시도는 온전한 제한을 갖는다.
    const timeoutSignal = AbortSignal.timeout(timeoutMs)
    const mergedSignal = signal ? AbortSignal.any([signal, timeoutSignal]) : timeoutSignal

    try {
      return await fetch(`${BASE_URL}${path}`, {
        ...rest,
        // 로그인 Set-Cookie 수신과 이후 동봉을 위해 명시한다. 배포에서 오리진이
        // 갈리는 경우 Backend CORS 의 allow-credentials 가 전제다(#79)
        credentials: 'include',
        signal: mergedSignal,
        // 헤더도 재시도마다 다시 만든다. 재발급으로 토큰이 바뀌었기 때문이다.
        headers: buildHeaders(headers),
      })
    } catch (cause) {
      rethrowIfAbortOrTimeout(cause)

      // 여기까지 왔으면 서버가 응답을 못 준 경우다(네트워크 끊김 등).
      // 서버가 거절한 것과는 성격이 다르므로 상태코드를 0 으로 표시해 구분한다.
      throw new ApiError(
        0,
        toFallbackErrorResponse('서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.'),
      )
    }
  }

  let response = await send()

  // Access Token 만료(401)면 재발급 후 원 요청을 딱 한 번 다시 보낸다.
  // 로그인·가입 실패(401)에 재발급은 무의미하고, 재발급 자신의 401 에 또 재발급하면
  // 무한 반복이다. 단 /api/auth/me 는 보호 API 라 재발급 대상이다.
  const skipRefresh =
    path === '/api/auth/login' || path === '/api/auth/signup' || path === '/api/auth/refresh'
  if (response.status === 401 && !skipRefresh) {
    const refreshed = await refreshSession()
    if (refreshed) {
      response = await send()
    }
  }

  if (!response.ok) {
    throw new ApiError(response.status, await readErrorBody(response))
  }

  return response
}

/**
 * 취소와 타임아웃만 골라내 원인에 맞게 다시 던진다. 그 외에는 아무것도 하지 않는다.
 *
 * 요청을 보낼 때뿐 아니라 **본문을 읽는 도중에도** 같은 오류가 날 수 있다.
 * 두 곳에서 따로 처리하면 한쪽만 고쳐져 규칙이 어긋나므로 한 함수로 모았다.
 *
 *  - `AbortError`   호출자가 스스로 끊은 것이다. 장애가 아니므로 그대로 다시 던져
 *                   호출부가 취소와 실패를 구분하게 한다. 화면을 벗어나며 폴링을
 *                   정리한 경우에 "서버에 연결할 수 없습니다" 가 뜨면 안 된다.
 *  - `TimeoutError` 서버가 제때 응답하지 못한 것이다. 서버가 거절한 것과 구분하려고
 *                   상태코드 0 으로 표시한다.
 */
function rethrowIfAbortOrTimeout(cause: unknown): void {
  if (!(cause instanceof DOMException)) return

  if (cause.name === 'AbortError') {
    throw cause
  }

  if (cause.name === 'TimeoutError') {
    throw new ApiError(
      0,
      toFallbackErrorResponse('서버 응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.'),
    )
  }
}

/**
 * 호출부가 넘긴 헤더에 기본값을 얹는다.
 *
 * `HeadersInit` 은 객체뿐 아니라 `Headers` 인스턴스와 `[key, value]` 튜플 배열도
 * 허용한다. 이것들을 객체 전개(`{ ...headers }`)로 합치면 조용히 망가진다.
 *  - `Headers` 인스턴스는 열거 가능한 속성이 없어 항목이 통째로 사라진다.
 *    나중에 Authorization 을 Headers 로 넘기면 인증이 빠진 채 401 만 받게 된다.
 *  - 튜플 배열은 `{ "0": [...], "1": [...] }` 같은 엉뚱한 헤더로 바뀐다.
 *
 * 그래서 반드시 `new Headers()` 로 정규화한 뒤 다룬다.
 */
function buildHeaders(headers: HeadersInit | undefined): Headers {
  const normalized = new Headers(headers)

  // 호출부가 Accept 를 직접 지정했다면 존중한다. 파일 다운로드처럼
  // JSON 이 아닌 응답을 받아야 하는 경우가 있다.
  if (!normalized.has('Accept')) {
    normalized.set('Accept', 'application/json')
  }

  // 로그인 상태면 인증 헤더를 붙인다. security: [] 인 인증 API 에도 붙지만
  // 서버는 무시하므로 해가 없고, 경로별 분기를 두는 것보다 단순하다.
  const accessToken = getAccessToken()
  if (accessToken !== null && !normalized.has('Authorization')) {
    normalized.set('Authorization', `Bearer ${accessToken}`)
  }

  return normalized
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
  } catch (cause) {
    // 여기서도 본문을 읽는 중이라 취소·타임아웃이 날 수 있다.
    // 그것까지 "요청을 처리하지 못했습니다" 로 덮으면 원인을 잃는다.
    rethrowIfAbortOrTimeout(cause)
    // 그 외에는 JSON 이 아니었다는 뜻이다. 아래 기본 메시지로 넘어간다.
  }
  return toFallbackErrorResponse(`요청을 처리하지 못했습니다. (HTTP ${response.status})`)
}
