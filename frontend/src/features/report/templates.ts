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
  /** 최신 HTML 템플릿 갤러리에 표시할 이름. 기존 API 표시 이름은 호환성을 위해 유지한다. */
  galleryName: string
  description: string
  category: '일반 보고서' | '개발 문서' | '학술' | '에디토리얼' | '컴팩트'
  preview: 'modern' | 'developer' | 'academic' | 'editorial' | 'side-panel' | 'minimal'
}

export const REPORT_TEMPLATES: readonly ReportTemplate[] = [
  {
    id: 'default',
    version: 1,
    name: '기본',
    galleryName: '모던 블루',
    description: '깔끔한 SaaS·프로젝트 보고서',
    category: '일반 보고서',
    preview: 'modern',
    className: 'tpl-default',
  },
  {
    id: 'github',
    version: 1,
    name: 'GitHub 문서',
    galleryName: '개발 문서',
    description: '코드와 기술 설명 중심',
    category: '개발 문서',
    preview: 'developer',
    className: 'tpl-github',
  },
  {
    id: 'latex',
    version: 1,
    name: 'LaTeX 학술',
    galleryName: '학술 보고서',
    description: '논문·연구 보고서 중심',
    category: '학술',
    preview: 'academic',
    className: 'tpl-latex',
  },
  {
    id: 'editorial-sage',
    version: 1,
    name: '에디토리얼',
    galleryName: '에디토리얼',
    description: '차분한 색과 긴 호흡의 스토리텔링',
    category: '에디토리얼',
    preview: 'editorial',
    className: 'tpl-editorial-sage',
  },
  {
    id: 'side-panel',
    version: 1,
    name: '사이드 패널',
    galleryName: '사이드 패널',
    description: '강한 표지와 구분된 정보 영역',
    category: '일반 보고서',
    preview: 'side-panel',
    className: 'tpl-side-panel',
  },
  {
    id: 'minimal-mono',
    version: 1,
    name: '미니멀 모노',
    galleryName: '미니멀 모노',
    description: '여백과 타이포그래피 중심',
    category: '컴팩트',
    preview: 'minimal',
    className: 'tpl-minimal-mono',
  },
  {
    id: 'compact',
    version: 1,
    name: '컴팩트',
    galleryName: '컴팩트 데이터',
    description: '정보를 밀도 있게 정리하는 구성',
    category: '컴팩트',
    preview: 'minimal',
    className: 'tpl-compact',
  },
  {
    id: 'research-report',
    version: 1,
    name: '리서치 리포트',
    galleryName: '리서치 리포트',
    description: '근거와 분석을 차분하게 보여주는 구성',
    category: '학술',
    preview: 'academic',
    className: 'tpl-research-report',
  },
  {
    id: 'business-report',
    version: 1,
    name: '비즈니스 리포트',
    galleryName: '비즈니스 리포트',
    description: '핵심 지표와 의사결정 중심',
    category: '일반 보고서',
    preview: 'modern',
    className: 'tpl-business-report',
  },
  {
    id: 'github-style',
    version: 1,
    name: 'GitHub 스타일',
    galleryName: 'GitHub 스타일',
    description: 'README와 변경 이력을 닮은 구성',
    category: '개발 문서',
    preview: 'developer',
    className: 'tpl-github-style',
  },
  {
    id: 'dark-tech',
    version: 1,
    name: '다크 테크',
    galleryName: '다크 테크',
    description: '기술 문서와 코드 블록을 강조',
    category: '개발 문서',
    preview: 'developer',
    className: 'tpl-dark-tech',
  },
  {
    id: 'portfolio',
    version: 1,
    name: '포트폴리오 케이스 스터디',
    galleryName: '포트폴리오 케이스 스터디',
    description: '문제·과정·결과를 한 장씩 보여주는 구성',
    category: '에디토리얼',
    preview: 'side-panel',
    className: 'tpl-portfolio',
  },
] as const

export function findTemplate(id: string | null): ReportTemplate | null {
  if (id === null) return null
  return REPORT_TEMPLATES.find((t) => t.id === id) ?? null
}

/** 템플릿 미선택(null) 상태에서 화면에 쓸 기본값. 저장은 사용자가 고른 뒤에만 한다. */
export const FALLBACK_TEMPLATE = REPORT_TEMPLATES[0]
