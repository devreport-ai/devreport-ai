/**
 * 보고서 디자인 템플릿 레지스트리.
 *
 * id 는 저장되는 값이라 바꾸지 않는다. 디자인이 바뀌면 version 을 올린다.
 * 디자인은 임시(팀 확정 대기)이며 report-document.css 의 값만 고치면 된다.
 */

export interface ReportTemplate {
  id: string
  version: number
  /** 화면에 보여줄 이름 */
  name: string
  /** 문서 루트에 붙는 CSS 클래스. 실제 디자인은 report-document.css 가 정의한다. */
  className: string
}

export const REPORT_TEMPLATES: readonly ReportTemplate[] = [
  { id: 'default', version: 1, name: '기본', className: 'tpl-default' },
  { id: 'github', version: 1, name: 'GitHub 문서', className: 'tpl-github' },
  { id: 'latex', version: 1, name: 'LaTeX 학술', className: 'tpl-latex' },
  { id: 'compact', version: 1, name: '컴팩트', className: 'tpl-compact' },
] as const

export function findTemplate(id: string | null): ReportTemplate | null {
  if (id === null) return null
  return REPORT_TEMPLATES.find((t) => t.id === id) ?? null
}

/** 템플릿 미선택(null) 상태에서 화면에 쓸 기본값. 저장은 사용자가 고른 뒤에만 한다. */
export const FALLBACK_TEMPLATE = REPORT_TEMPLATES[0]
