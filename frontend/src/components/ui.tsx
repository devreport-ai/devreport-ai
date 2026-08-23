import type { ReactNode } from 'react'

export type IconName =
  | 'folder'
  | 'file-text'
  | 'upload'
  | 'sparkles'
  | 'chevron-right'
  | 'chevron-down'
  | 'x'
  | 'pencil'
  | 'trash'
  | 'external'
  | 'undo'
  | 'redo'
  | 'download'
  | 'grip'
  | 'plus'
  | 'check'
  | 'info'
  | 'alert'
  | 'edit'
  | 'eye'
  | 'key'

export function Icon({ name, size = 16 }: { name: IconName; size?: number }) {
  const common = {
    width: size,
    height: size,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.8,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    'aria-hidden': true,
  }

  const paths: Record<IconName, ReactNode> = {
    folder: (
      <>
        <path d="M3 6.5A2.5 2.5 0 0 1 5.5 4H10l2 2h6.5A2.5 2.5 0 0 1 21 8.5v8A2.5 2.5 0 0 1 18.5 19h-13A2.5 2.5 0 0 1 3 16.5z" />
        <path d="M3 9h18" />
      </>
    ),
    'file-text': (
      <>
        <path d="M14 2H6.5A2.5 2.5 0 0 0 4 4.5v15A2.5 2.5 0 0 0 6.5 22h11a2.5 2.5 0 0 0 2.5-2.5V8z" />
        <path d="M14 2v6h6M8 13h8M8 17h6" />
      </>
    ),
    upload: (
      <>
        <path d="M12 16V4" />
        <path d="m7 9 5-5 5 5" />
        <path d="M5 20h14" />
      </>
    ),
    sparkles: (
      <>
        <path d="m12 3-1.2 4.8L6 9l4.8 1.2L12 15l1.2-4.8L18 9l-4.8-1.2z" />
        <path d="m19 15-.6 2.4L16 18l2.4.6L19 21l.6-2.4L22 18l-2.4-.6z" />
      </>
    ),
    'chevron-right': <path d="m9 18 6-6-6-6" />,
    'chevron-down': <path d="m6 9 6 6 6-6" />,
    x: (
      <>
        <path d="m6 6 12 12M18 6 6 18" />
      </>
    ),
    pencil: (
      <>
        <path d="m4 20 4.2-1 10-10a2.1 2.1 0 0 0-3-3l-10 10z" />
        <path d="m13.5 7.5 3 3" />
      </>
    ),
    trash: (
      <>
        <path d="M4 7h16M10 11v6M14 11v6M6 7l1 14h10l1-14M9 7V4h6v3" />
      </>
    ),
    external: (
      <>
        <path d="M14 5h5v5M19 5l-8 8" />
        <path d="M19 13v4.5A2.5 2.5 0 0 1 16.5 20h-10A2.5 2.5 0 0 1 4 17.5v-10A2.5 2.5 0 0 1 6.5 5H11" />
      </>
    ),
    undo: (
      <>
        <path d="M9 7 4 12l5 5" />
        <path d="M4 12h9a6 6 0 0 1 6 6" />
      </>
    ),
    redo: (
      <>
        <path d="m15 7 5 5-5 5" />
        <path d="M20 12h-9a6 6 0 0 0-6 6" />
      </>
    ),
    download: (
      <>
        <path d="M12 3v12" />
        <path d="m7 10 5 5 5-5" />
        <path d="M5 21h14" />
      </>
    ),
    grip: (
      <>
        <path d="M9 5h.01M15 5h.01M9 12h.01M15 12h.01M9 19h.01M15 19h.01" />
      </>
    ),
    plus: (
      <>
        <path d="M12 5v14M5 12h14" />
      </>
    ),
    check: <path d="m5 12 4 4L19 6" />,
    info: (
      <>
        <circle cx="12" cy="12" r="9" />
        <path d="M12 11v5M12 8h.01" />
      </>
    ),
    alert: (
      <>
        <path d="M10.3 3.8 2.7 17a2 2 0 0 0 1.7 3h15.2a2 2 0 0 0 1.7-3L13.7 3.8a2 2 0 0 0-3.4 0Z" />
        <path d="M12 9v4M12 17h.01" />
      </>
    ),
    edit: (
      <>
        <path d="M4 20h4l10.5-10.5a2.1 2.1 0 0 0-3-3L5 17v3Z" />
        <path d="m13.5 7.5 3 3" />
      </>
    ),
    key: (
      <>
        <circle cx="8" cy="15" r="4" />
        <path d="m10.8 12.2 8.7-8.7M16 7l2 2M13.5 9.5l2 2" />
      </>
    ),
    eye: (
      <>
        <path d="M2.5 12s3.2-5 9.5-5 9.5 5 9.5 5-3.2 5-9.5 5-9.5-5-9.5-5Z" />
        <circle cx="12" cy="12" r="2" />
      </>
    ),
  }

  return <svg {...common}>{paths[name]}</svg>
}

export function AppTopBar({
  children,
  current,
  root = '워크스페이스',
}: {
  children?: ReactNode
  current: string
  root?: string
}) {
  return (
    <div className="app-topbar">
      <div className="app-breadcrumb">
        <span>{root}</span>
        <span className="app-breadcrumb__slash">/</span>
        <strong>{current}</strong>
      </div>
      {children && <div className="app-topbar__actions">{children}</div>}
    </div>
  )
}

export function BrandMark({ compact = false }: { compact?: boolean }) {
  return (
    <span className={`brand-mark${compact ? ' brand-mark--compact' : ''}`}>
      <span className="brand-mark__glyph" aria-hidden>
        D
      </span>
      <span className="brand-mark__name">DevReport AI</span>
    </span>
  )
}

export function AuthLayout({
  title,
  subtitle,
  children,
  footer,
}: {
  title: string
  subtitle: string
  children: ReactNode
  footer: ReactNode
}) {
  return (
    <main className="auth-screen" aria-labelledby="auth-title">
      <aside className="auth-screen__brand">
        <BrandMark />
        <div className="auth-screen__copy">
          <h2>개발 자료를 정리하고, 완성도 높은 보고서로 전환하세요.</h2>
          <p>파일 분석부터 문서 편집과 PDF 출력까지 하나의 작업 흐름에서 이어집니다.</p>
        </div>
      </aside>
      <section className="auth-screen__content">
        <div className="auth-card">
          <header className="auth-card__header">
            <p className="eyebrow">DEVREPORT AI</p>
            <h1 id="auth-title">{title}</h1>
            <p>{subtitle}</p>
          </header>
          {children}
          <footer className="auth-card__footer">{footer}</footer>
        </div>
      </section>
    </main>
  )
}

export function PageHeader({
  eyebrow,
  title,
  description,
  actions,
}: {
  eyebrow?: string
  title: string
  description?: string
  actions?: ReactNode
}) {
  return (
    <header className="page-header">
      <div>
        {eyebrow && <p className="eyebrow">{eyebrow}</p>}
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {actions && <div className="page-header__actions">{actions}</div>}
    </header>
  )
}

export function SurfaceCard({
  children,
  className = '',
  as: Component = 'section',
}: {
  children: ReactNode
  className?: string
  as?: 'section' | 'div' | 'article'
}) {
  return <Component className={`surface-card ${className}`}>{children}</Component>
}
