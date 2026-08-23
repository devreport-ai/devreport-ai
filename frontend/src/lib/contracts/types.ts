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

export type SignupRequest = Schemas['SignupRequest']
export type LoginRequest = Schemas['LoginRequest']
export type PasswordChangeRequest = Schemas['PasswordChangeRequest']
export type PasswordResetRequest = Schemas['PasswordResetRequest']
export type PasswordResetRequestedResponse = Schemas['PasswordResetRequestedResponse']
export type PasswordResetConfirmRequest = Schemas['PasswordResetConfirmRequest']
export type TokenResponse = Schemas['TokenResponse']
export type UserResponse = Schemas['UserResponse']

export type ProjectResponse = Schemas['ProjectResponse']
export type ProjectPageResponse = Schemas['ProjectPageResponse']
export type TrashedProjectPageResponse = Schemas['TrashedProjectPageResponse']
export type ProjectRequest = Schemas['ProjectRequest']
export type ProjectIdResponse = Schemas['ProjectIdResponse']

export type FileResponse = Schemas['FileResponse']
export type FilePageResponse = Schemas['FilePageResponse']
export type FileIdResponse = Schemas['FileIdResponse']

/**
 * 생성 타입 보정 두 가지:
 * 1. openapi-typescript 가 JSON Schema 의 $defs 를 데이터 속성으로 포함시킨다 — 실제
 *    응답에는 없으므로 걷어낸다.
 * 2. ReportUpdateRequest 의 oneOf(templateId·Version 둘 다 null 또는 둘 다 값)가
 *    사용 불가능한 교차 타입으로 생성된다. 계약 그대로 손으로 옮긴다.
 */
export type ReportDocument = Omit<Schemas['report-document.schema'], '$defs'>
export type Report = Omit<Schemas['report.schema'], 'document' | '$defs'> & {
  document: ReportDocument
}
export type ReportSummaryResponse = Schemas['ReportSummaryResponse']
export type ReportPageResponse = Schemas['ReportPageResponse']
export type ReportSection = Schemas['section']
export type ReportBlock = Schemas['block']
export type ReportExportResponse = Schemas['ReportExportResponse']
export type ReportRenderData = Omit<Schemas['ReportRenderDataResponse'], 'document'> & {
  document: ReportDocument
}
export interface ReportUpdateRequest {
  document: ReportDocument
  templateId: string | null
  templateVersion: number | null
  presentationSettings: Record<string, string | number | boolean | null>
  expectedVersion: number
}

export type GenerationRequest = Schemas['GenerationRequest']

/**
 * 생성 요청에 실을 metadata.
 *
 * 결과물의 `ReportDocument.metadata` 와 같은 형태이며 그대로 보고서 표지에 쓰인다.
 * 계약이 두 곳을 같은 스키마로 맞춰 두었으므로 여기서는 생성된 타입을 그대로 쓴다.
 */
export type GenerationMetadata = GenerationRequest['metadata']
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
 *
 * MIME 을 `text/` 프리픽스로 보면 계약에 없는 `text/html` 까지 통과한다.
 * 계약이 정한 형식만 그대로 나열한다.
 */
const AI_INPUT_MIME_TYPES = [
  'image/png',
  'image/jpeg',
  'text/plain',
  'text/markdown',
  'application/zip',
]
const AI_INPUT_EXTENSIONS = ['.zip', '.md', '.txt', '.png', '.jpg', '.jpeg']

/** 이 파일을 AI 분석 대상으로 고를 수 있는가. */
export function isAiInputFile(file: Pick<FileResponse, 'contentType' | 'originalName'>): boolean {
  const name = file.originalName.toLowerCase()
  if (AI_INPUT_EXTENSIONS.some((ext) => name.endsWith(ext))) return true
  // 확장자가 없는 경우를 대비해 MIME 으로도 한 번 본다.
  // `text/plain; charset=utf-8` 처럼 파라미터가 붙어 오므로 앞부분만 떼어 비교한다.
  const mime = file.contentType.split(';')[0].trim().toLowerCase()
  return AI_INPUT_MIME_TYPES.includes(mime)
}
