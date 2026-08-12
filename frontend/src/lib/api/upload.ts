/**
 * 파일 업로드 전용 HTTP 함수.
 *
 * `client.ts` 의 `apiFetch` 를 쓰지 않고 XHR 을 쓰는 이유는 하나뿐이다.
 * **`fetch` 는 업로드 진행률을 알려주지 않는다.** 표준에 업로드 progress 이벤트가 없다.
 * 파일이 최대 20 MiB 라 느린 회선에서는 실제로 오래 걸리므로 퍼센트 표시가 필요하다
 * (이슈 #36 작업 범위: "업로드 진행률·성공·실패 표시").
 *
 * "모든 HTTP 는 lib/api 를 거친다" 는 규칙은 그대로다. 창구가 둘이 된 게 아니라
 * 같은 폴더 안에 용도별 함수가 둘인 것이다.
 */
import { ApiError, isErrorResponse, toFallbackErrorResponse } from './errors'
import type { FileIdResponse } from '../contracts/types'

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

/** 업로드 상한. 20 MiB 를 느린 회선에서 올리는 경우를 감안한 값이다. */
const UPLOAD_TIMEOUT_MS = 5 * 60 * 1000

export interface UploadOptions {
  /** 0~1. 서버가 전체 크기를 알려주지 않으면 호출되지 않는다. */
  onProgress?: (ratio: number) => void
  /** 화면을 벗어나거나 사용자가 취소할 때 끊는다. */
  signal?: AbortSignal
}

/**
 * 파일 하나를 업로드한다. 계약상 한 번에 한 개씩만 받는다.
 *
 * 여러 개를 올릴 때는 호출부가 이 함수를 병렬로 부른다 (이슈 #36).
 *
 * @throws {ApiError} 서버가 거절했거나 연결에 실패했을 때
 * @throws {DOMException} 호출자가 취소했을 때 (AbortError) — `apiFetch` 와 같은 규칙
 */
export function uploadProjectFile(
  projectId: string,
  file: File,
  options: UploadOptions = {},
): Promise<FileIdResponse> {
  const { onProgress, signal } = options

  return new Promise<FileIdResponse>((resolve, reject) => {
    // 이미 취소된 신호를 받고 시작하는 경우가 있다. 요청을 보내기 전에 끊는다.
    if (signal?.aborted) {
      reject(new DOMException('Aborted', 'AbortError'))
      return
    }

    const form = new FormData()
    // 계약이 정한 필드명. 바꾸면 서버가 못 알아본다.
    form.append('file', file)

    const xhr = new XMLHttpRequest()
    // 이 값을 안 주면 기본값 0(무제한)이라 아래 ontimeout 이 영원히 실행되지 않는다.
    xhr.timeout = UPLOAD_TIMEOUT_MS
    xhr.open('POST', `${BASE_URL}/api/projects/${projectId}/files`)
    xhr.responseType = 'text'
    xhr.setRequestHeader('Accept', 'application/json')

    // Content-Type 을 직접 지정하지 않는다. FormData 를 넘기면 브라우저가
    // multipart 경계 문자열을 포함한 헤더를 알아서 만든다. 직접 쓰면 경계가 빠져 깨진다.

    if (onProgress) {
      xhr.upload.onprogress = (event) => {
        // lengthComputable 이 false 면 전체 크기를 몰라 비율을 낼 수 없다.
        if (event.lengthComputable) onProgress(event.loaded / event.total)
      }
    }

    const abort = () => xhr.abort()
    signal?.addEventListener('abort', abort)
    const cleanup = () => signal?.removeEventListener('abort', abort)

    xhr.onload = () => {
      cleanup()
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          resolve(JSON.parse(xhr.responseText) as FileIdResponse)
        } catch {
          reject(
            new ApiError(xhr.status, toFallbackErrorResponse('서버 응답을 해석하지 못했습니다.')),
          )
        }
        return
      }
      reject(new ApiError(xhr.status, parseErrorBody(xhr.responseText, xhr.status)))
    }

    // 사용자가 끊은 것과 네트워크가 끊긴 것을 구분한다. `apiFetch` 와 같은 규칙이다.
    xhr.onabort = () => {
      cleanup()
      reject(new DOMException('Aborted', 'AbortError'))
    }

    xhr.onerror = () => {
      cleanup()
      reject(
        new ApiError(
          0,
          toFallbackErrorResponse('서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.'),
        ),
      )
    }

    xhr.ontimeout = () => {
      cleanup()
      reject(
        new ApiError(0, toFallbackErrorResponse('업로드가 지연되고 있습니다. 다시 시도해 주세요.')),
      )
    }

    xhr.send(form)
  })
}

/** 실패 응답 본문을 계약의 ErrorResponse 로 해석한다. 규격 밖이면 기본 메시지로 폴백한다. */
function parseErrorBody(text: string, status: number) {
  try {
    const body: unknown = JSON.parse(text)
    if (isErrorResponse(body)) return body
  } catch {
    // JSON 이 아니었다는 뜻이다.
  }
  return toFallbackErrorResponse(`업로드를 처리하지 못했습니다. (HTTP ${status})`)
}
