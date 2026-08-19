import type { ReactNode } from 'react'

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
