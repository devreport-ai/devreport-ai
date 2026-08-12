/**
 * 계약에서 생성된 타입 중 화면에서 실제로 쓰는 것만 짧은 이름으로 다시 내보낸다.
 *
 * `api.ts` 는 `openapi-typescript` 가 만든 1,800줄짜리 파일이라 손대지 않는다.
 * 대신 화면 코드가 `components['schemas']['GenerationJobResponse']` 같은 긴 경로를
 * 매번 쓰지 않도록 여기서 한 겹 얇게 감싼다.
 *
 * 계약이 바뀌면 `npm run gen:types` 를 다시 돌린다. 이름이 사라졌으면 여기서 컴파일 에러가 난다.
 * 그게 이 파일의 목적이다 — 계약 변경을 조용히 지나치지 않게 하는 것.
 */
import type { components } from './api'

type Schemas = components['schemas']

export type ProjectResponse = Schemas['ProjectResponse']
export type ProjectPageResponse = Schemas['ProjectPageResponse']
export type ProjectRequest = Schemas['ProjectRequest']
export type ProjectIdResponse = Schemas['ProjectIdResponse']

export type FileResponse = Schemas['FileResponse']
export type FilePageResponse = Schemas['FilePageResponse']
export type FileIdResponse = Schemas['FileIdResponse']

export type GenerationRequest = Schemas['GenerationRequest']

/**
 * 생성 요청에 실을 metadata.
 *
 * TODO(contract): 계약이 `metadata` 를 `type: object` 로만 두고 형태를 정의하지 않았다.
 * 생성된 타입이 `Record<string, never>` 라 아무것도 넣을 수 없다.
 * `ReportDocument.metadata`(`{ title, author?, course?, date? }`)가 이 값으로 채워지는
 * 유일한 대상이므로 같은 모양으로 보낸다. 계약에 스키마 반영을 요청해야 한다.
 */
export interface GenerationMetadata {
  title: string
  author?: string
  course?: string
  date?: string
}

/** 위 TODO 가 해결되면 이 함수를 지우고 타입을 그대로 쓴다. */
export function toGenerationRequest(input: {
  fileIds: string[]
  metadata: GenerationMetadata
  instructions: string
}): GenerationRequest {
  return input as unknown as GenerationRequest
}
export type GenerationJobResponse = Schemas['GenerationJobResponse']
export type JobIdResponse = Schemas['JobIdResponse']

/** 생성 작업이 더 이상 진행되지 않는 상태. 폴링을 멈출 기준이다. */
export const TERMINAL_JOB_STATUSES = ['COMPLETED', 'FAILED', 'CANCELED'] as const

export function isTerminalStatus(status: GenerationJobResponse['status']): boolean {
  return (TERMINAL_JOB_STATUSES as readonly string[]).includes(status)
}

/**
 * AI 가 실제로 읽는 파일 형식.
 *
 * 업로드 API 는 PDF·DOCX 도 받지만 AI 생성 입력에서는 제외된다
 * (`contracts/report-generation.md`). 올려는 두되 분석 대상으로는 못 고르게 해야
 * "PDF 올렸는데 왜 반영이 안 되지" 를 막을 수 있다.
 */
const AI_INPUT_MIME_PREFIXES = ['image/png', 'image/jpeg', 'text/', 'application/zip']
const AI_INPUT_EXTENSIONS = ['.zip', '.md', '.txt', '.png', '.jpg', '.jpeg']

/** 이 파일을 AI 분석 대상으로 고를 수 있는가. */
export function isAiInputFile(file: Pick<FileResponse, 'contentType' | 'originalName'>): boolean {
  const name = file.originalName.toLowerCase()
  if (AI_INPUT_EXTENSIONS.some((ext) => name.endsWith(ext))) return true
  // 확장자가 없거나 특이한 경우를 대비해 MIME 으로도 한 번 본다.
  return AI_INPUT_MIME_PREFIXES.some((prefix) => file.contentType.startsWith(prefix))
}
