/**
 * 자동 저장 — 편집이 멈추고 1.5초 뒤에 저장한다.
 *
 * 저장마다 서버 version 이 올라가고, 다음 저장은 그 version 을 expectedVersion 으로
 * 보낸다. 불일치면 409 REPORT_VERSION_CONFLICT — 다른 곳에서 고쳐진 것이므로
 * 덮어쓰지 않고 충돌 상태로 멈춘다.
 */
import { useEffect, useRef, useState } from 'react'
import { ApiError } from '../../lib/api/errors'
import { saveReport } from './api'
import type { ReportDocument } from '../../lib/contracts/types'

const AUTOSAVE_DEBOUNCE_MS = 1500

export type SaveStatus = 'idle' | 'saving' | 'saved' | 'conflict' | 'error'

export interface AutosaveInput {
  reportId: string
  document: ReportDocument
  templateId: string | null
  templateVersion: number | null
  presentationSettings: Record<string, string | number | boolean | null>
  /**
   * 서버가 알고 있는 마지막 상태(GET 응답). 여기서 어긋난 것이 저장 대상이다.
   * 현재 입력값으로 초기화하면 생성 흐름에서 고른 템플릿이 "이미 저장됨" 취급되어
   * 편집 전까지 저장이 안 나가고 새로고침 시 선택이 사라진다.
   */
  server: {
    document: ReportDocument
    templateId: string | null
    templateVersion: number | null
    version: number
  }
}

export function useAutosave(input: AutosaveInput): { status: SaveStatus } {
  const { reportId, document, templateId, templateVersion, presentationSettings } = input
  const [status, setStatus] = useState<SaveStatus>('idle')

  // 마지막으로 저장에 성공한 내용과 version. state 로 두면 저장 성공마다
  // effect 가 다시 돌아 불필요한 저장이 이어지므로 ref 로 둔다.
  const saved = useRef({
    document: input.server.document,
    templateId: input.server.templateId,
    templateVersion: input.server.templateVersion,
    version: input.server.version,
  })
  useEffect(() => {
    const dirty =
      document !== saved.current.document ||
      templateId !== saved.current.templateId ||
      templateVersion !== saved.current.templateVersion
    if (!dirty) return
    // 충돌 후에는 자동 저장을 멈춘다. 계속 시도해봐야 같은 409 다.
    if (saved.current.version < 0) return

    const timer = setTimeout(() => {
      setStatus('saving')
      saveReport(reportId, {
        document,
        templateId,
        templateVersion,
        presentationSettings,
        expectedVersion: saved.current.version,
      }).then(
        (report) => {
          saved.current = { document, templateId, templateVersion, version: report.version }
          setStatus('saved')
        },
        (error: unknown) => {
          if (error instanceof ApiError && error.status === 409) {
            saved.current.version = -1 // 충돌 뒤 자동 저장 중단 표식
            setStatus('conflict')
            return
          }
          // 일시 오류(네트워크 등)는 다음 편집 때 자연히 재시도된다
          setStatus('error')
        },
      )
    }, AUTOSAVE_DEBOUNCE_MS)

    return () => clearTimeout(timer)
  }, [reportId, document, templateId, templateVersion, presentationSettings])

  return { status }
}
