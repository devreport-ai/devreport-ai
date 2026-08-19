/** 편집 미리보기와 PDF가 공유하는 실제 HTML 템플릿 렌더러. */
import type { ReportBlock, ReportDocument } from '../../lib/contracts/types'
import { BlockView } from './BlockView'
import type { ReportTemplate } from './templates'
import '@fontsource-variable/geist'
import '@fontsource-variable/geist-mono'
import '@fontsource-variable/noto-serif-kr'

const TEMPLATE_LABELS: Record<string, string> = {
  default: 'DEVREPORT AI',
  github: 'DOCS / REPORTS / IMPLEMENTATION.MD',
  'github-style': 'DEVREPORT-AI / PROJECT-REPORT',
  latex: 'RESEARCH PAPER',
  'research-report': 'RESEARCH NOTE · 2026/08',
  'business-report': 'EXECUTIVE REPORT',
  'editorial-sage': 'PROJECT JOURNAL · VOL. 01',
  'side-panel': 'DEVREPORT AI · CASE STUDY',
  'minimal-mono': 'REPORT / 009',
  compact: 'DATA REPORT',
  'dark-tech': 'SYSTEM REPORT // 011',
  portfolio: 'CASE STUDY 2026',
}

export function ReportDocumentView({
  document: report,
  template,
  imageUrls = {},
  className = '',
  onEditBlock,
}: {
  document: ReportDocument
  template: ReportTemplate
  imageUrls?: Record<string, string>
  className?: string
  onEditBlock?: (block: ReportBlock) => void
}) {
  const blockCount = report.sections.reduce((count, section) => count + section.blocks.length, 0)
  const label = TEMPLATE_LABELS[template.id] ?? template.galleryName.toUpperCase()

  return (
    <div className={`report-sheet report-document ${template.className} ${className}`.trim()}>
      {template.id === 'side-panel' && (
        <aside className="rpt-side-rail" aria-hidden>
          <span className="rpt-side-rail__mark">D</span>
          <strong>{report.metadata.title}</strong>
          <span>{label}</span>
          <dl>
            <div>
              <dt>작성자</dt>
              <dd>{report.metadata.author ?? '—'}</dd>
            </div>
            <div>
              <dt>기간</dt>
              <dd>{report.metadata.date ?? '—'}</dd>
            </div>
            <div>
              <dt>상태</dt>
              <dd>완료</dd>
            </div>
          </dl>
          <small>DEVREPORT.AI</small>
        </aside>
      )}

      <div className="rpt-document-shell">
        {['github', 'github-style', 'dark-tech'].includes(template.id) && (
          <div className="rpt-document-chrome" aria-hidden>
            <span>{label}</span>
            <b>SUCCESS</b>
          </div>
        )}

        <header className="rpt-document-header">
          <div className="rpt-document-heading">
            <p className="rpt-kicker">{label}</p>
            <h1>{report.metadata.title}</h1>
            <MetaLine metadata={report.metadata} />
          </div>
          {['editorial-sage', 'portfolio'].includes(template.id) && (
            <div className="rpt-hero-visual" aria-hidden>
              <span>PROJECT</span>
              <b>IMAGE / 01</b>
              <i />
              <i />
              <i />
            </div>
          )}
        </header>

        {template.id === 'dark-tech' && (
          <div className="rpt-pipeline" aria-hidden>
            {['INGEST', 'GENERATE', 'RENDER', 'EDIT'].map((stage, index) => (
              <span key={stage}>
                <b>0{index + 1}</b>
                {stage}
              </span>
            ))}
          </div>
        )}

        {['research-report', 'business-report', 'compact', 'dark-tech'].includes(template.id) && (
          <div className="rpt-stat-strip" aria-hidden>
            <span>
              <b>{report.sections.length}</b>
              SECTIONS
            </span>
            <span>
              <b>{blockCount}</b>
              BLOCKS
            </span>
            <span>
              <b>{template.id === 'compact' ? '94' : '100%'}</b>
              {template.id === 'compact' ? 'SCORE' : 'READY'}
            </span>
          </div>
        )}

        <div className="rpt-content-layout">
          {template.id === 'github' && (
            <nav className="rpt-toc" aria-label="문서 목차">
              <strong>CONTENTS</strong>
              {report.sections.map((section, index) => (
                <span key={section.id}>
                  <b>{String(index + 1).padStart(2, '0')}</b>
                  {section.title}
                </span>
              ))}
            </nav>
          )}

          <div className="rpt-sections">
            {report.sections.map((section, index) => (
              <section key={section.id} className="rpt-section">
                <h2>
                  <span className="rpt-section-number">{String(index + 1).padStart(2, '0')}</span>
                  <span>{section.title}</span>
                </h2>
                {section.blocks.map((block) => (
                  <ReportBlockView
                    key={block.id}
                    block={block}
                    imageUrl={block.type === 'image' ? imageUrls[block.fileId] : undefined}
                    onEdit={onEditBlock}
                  />
                ))}
              </section>
            ))}
          </div>
        </div>

        <footer className="rpt-document-footer" aria-hidden>
          <span>DEVREPORT AI</span>
          <span>{template.galleryName}</span>
          <b>01</b>
        </footer>
      </div>
    </div>
  )
}

function ReportBlockView({
  block,
  imageUrl,
  onEdit,
}: {
  block: ReportBlock
  imageUrl?: string
  onEdit?: (block: ReportBlock) => void
}) {
  if (!onEdit) {
    return (
      <div className="rpt-block">
        <BlockView block={block} imageUrl={imageUrl} />
      </div>
    )
  }

  return (
    <div
      role="button"
      tabIndex={0}
      className="rpt-block editor-preview-block"
      onClick={() => onEdit(block)}
      onKeyDown={(event) => {
        if (event.key !== 'Enter' && event.key !== ' ') return
        event.preventDefault()
        onEdit(block)
      }}
    >
      <BlockView block={block} imageUrl={imageUrl} />
    </div>
  )
}

function MetaLine({ metadata }: { metadata: ReportDocument['metadata'] }) {
  const parts = [metadata.author, metadata.course, metadata.date].filter(Boolean)
  if (parts.length === 0) return null
  return <p className="rpt-meta">{parts.join(' · ')}</p>
}
