/** 편집 미리보기와 PDF가 공유하는 실제 HTML 템플릿 렌더러. */
import type { ReportBlock, ReportDocument } from '../../lib/contracts/types'
import { BlockView } from './BlockView'
import type { ReportTemplate } from './templates'
import '@fontsource-variable/geist'
import '@fontsource-variable/geist-mono'
import '@fontsource-variable/noto-serif-kr'

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
  const label = template.label

  return (
    <div className={`report-sheet report-document ${template.className} ${className}`.trim()}>
      {template.sideRail && (
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
              <dt>형식</dt>
              <dd>HTML</dd>
            </div>
          </dl>
          <small>DEVREPORT.AI</small>
        </aside>
      )}

      <div className="rpt-document-shell">
        {template.chrome && (
          <div className="rpt-document-chrome" aria-hidden>
            <span>{label}</span>
            <b>HTML</b>
          </div>
        )}

        <header className="rpt-document-header">
          <div className="rpt-document-heading">
            <p className="rpt-kicker">{label}</p>
            <h1>{report.metadata.title}</h1>
            <MetaLine metadata={report.metadata} />
          </div>
          {template.heroVisual && (
            <div className="rpt-hero-visual" aria-hidden>
              <span>PROJECT</span>
              <b>IMAGE / 01</b>
              <i />
              <i />
              <i />
            </div>
          )}
        </header>

        {template.pipeline && (
          <div className="rpt-pipeline" aria-hidden>
            {['INGEST', 'GENERATE', 'RENDER', 'EDIT'].map((stage, index) => (
              <span key={stage}>
                <b>0{index + 1}</b>
                {stage}
              </span>
            ))}
          </div>
        )}

        {template.statStrip && (
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
              <b>HTML</b>
              FORMAT
            </span>
          </div>
        )}

        <div className="rpt-content-layout">
          {template.toc && (
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
          <b>A4</b>
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
    <div className="rpt-block editor-preview-block">
      <BlockView block={block} imageUrl={imageUrl} />
      <button type="button" className="rpt-block__edit" onClick={() => onEdit(block)}>
        이 블록 편집
      </button>
    </div>
  )
}

function MetaLine({ metadata }: { metadata: ReportDocument['metadata'] }) {
  const parts = [metadata.author, metadata.course, metadata.date].filter(Boolean)
  if (parts.length === 0) return null
  return <p className="rpt-meta">{parts.join(' · ')}</p>
}
