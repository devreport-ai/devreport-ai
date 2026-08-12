/** 블록 7종의 읽기 전용 렌더링. 편집기와 A4 미리보기, #39 출력 페이지가 공유한다. */
import { InlineMarkdown } from '../../lib/markdown/InlineMarkdown'
import type { ReportBlock } from '../../lib/contracts/types'

export function BlockView({ block, imageUrl }: { block: ReportBlock; imageUrl?: string }) {
  switch (block.type) {
    case 'paragraph':
      return (
        <p>
          <InlineMarkdown text={block.content} />
        </p>
      )
    case 'bulletList':
      return (
        <ul>
          {block.items.map((item, i) => (
            <li key={i}>
              <InlineMarkdown text={item} />
            </li>
          ))}
        </ul>
      )
    case 'code':
      return (
        <pre>
          <code>{block.code}</code>
        </pre>
      )
    case 'table':
      return (
        <table>
          <thead>
            <tr>
              {block.columns.map((col, i) => (
                <th key={i}>
                  <InlineMarkdown text={col} />
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {block.rows.map((row, r) => (
              <tr key={r}>
                {row.map((cell, c) => (
                  <td key={c}>
                    <InlineMarkdown text={cell} />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      )
    case 'image':
      return (
        <figure>
          {/* 파일 API 는 인증이 필요해 <img src> 로 직접 못 부른다. 호출부가 blob URL 을 만든다. */}
          {imageUrl ? (
            <img src={imageUrl} alt={block.alt} />
          ) : (
            <p aria-label={block.alt}>🖼 {block.alt}</p>
          )}
          {block.caption && <figcaption>{block.caption}</figcaption>}
        </figure>
      )
    case 'callout':
      return (
        <div className="rpt-callout">
          {block.title && <div className="rpt-callout-title">{block.title}</div>}
          <InlineMarkdown text={block.content} />
        </div>
      )
    case 'pageBreak':
      return <hr className="rpt-pagebreak" aria-label="페이지 나누기" />
  }
}
