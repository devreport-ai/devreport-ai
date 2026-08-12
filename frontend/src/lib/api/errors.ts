/**
 * Backend 공통 에러 응답 계약과, 그것을 감싸 던지기 위한 예외 타입.
 *
 * Backend 는 실패 시 HTTP 상태코드와 함께 항상 아래 모양의 JSON 을 돌려준다
 * (contracts/openapi.yaml 의 ErrorResponse 스키마).
 *
 *   { "code": "FILE_TOO_LARGE", "message": "...", "details": null, "timestamp": "..." }
 *
 * 화면에 보여줄 문구는 `message`, 코드에서 분기할 때는 `code` 를 쓴다.
 * 상태코드(404, 413 …)로 분기하지 않는 이유는 같은 상태코드에 여러 원인이
 * 매달려 있기 때문이다. 예를 들어 404 하나에 PROJECT_NOT_FOUND 와
 * FILE_NOT_FOUND 가 함께 붙는다.
 */

/** contracts/openapi.yaml 의 ErrorResponse 스키마와 1:1 대응한다. */
export interface ErrorResponse {
  code: string
  message: string
  /** 계약상 nullable. 검증 실패 상세 등이 담길 수 있다. */
  details: unknown | null
  timestamp: string
}

/**
 * API 호출 실패를 나타내는 예외.
 *
 * 일반 Error 대신 별도 클래스로 만든 이유는, 호출하는 쪽에서
 * `instanceof ApiError` 로 "서버가 규격에 맞게 거절한 것"과
 * "네트워크가 끊긴 것"을 구분해야 하기 때문이다.
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly details: unknown | null

  constructor(status: number, body: ErrorResponse) {
    // Error 의 message 에 서버 문구를 그대로 넣어두면 콘솔·로그에서 바로 읽힌다.
    super(body.message)
    this.name = 'ApiError'
    this.status = status
    this.code = body.code
    this.details = body.details
  }
}

/**
 * 응답 본문이 ErrorResponse 규격인지 검사한다.
 *
 * 서버가 항상 규격을 지킬 것이라고 가정하면 안 된다. 프록시나 게이트웨이가
 * HTML 오류 페이지를 대신 돌려주는 경우가 실제로 있고, 그때 body.code 를
 * 그냥 읽으면 undefined 가 흘러들어가 엉뚱한 곳에서 터진다.
 *
 * 계약상 필수인 `code`·`message`·`timestamp` 세 개를 모두 확인한다.
 * `timestamp` 를 빼고 검사하면, 타입은 string 이라고 선언해 놓고 실제로는
 * undefined 가 담긴 객체가 통과해 버린다.
 */
export function isErrorResponse(value: unknown): value is ErrorResponse {
  if (typeof value !== 'object' || value === null) return false
  const candidate = value as Record<string, unknown>
  return (
    typeof candidate.code === 'string' &&
    typeof candidate.message === 'string' &&
    typeof candidate.timestamp === 'string'
  )
}

/**
 * 알 수 없는 실패를 ErrorResponse 모양으로 바꿔준다.
 *
 * 규격 밖 응답이 와도 화면에서는 결국 message 하나만 보여주면 되므로,
 * 호출부가 예외 처리를 두 갈래로 나누지 않도록 여기서 형태를 통일한다.
 */
export function toFallbackErrorResponse(message: string): ErrorResponse {
  return {
    code: 'UNKNOWN_ERROR',
    message,
    details: null,
    timestamp: new Date().toISOString(),
  }
}
