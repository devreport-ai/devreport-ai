/** 인라인 마크다운을 React 요소로 렌더링한다. innerHTML 을 쓰지 않으므로 XSS 가 없다. */
import { Fragment } from 'react'
import { parseInline } from './inline'

export function InlineMarkdown({ text }: { text: string }) {
  return (
    <>
      {parseInline(text).map((node, i) => {
        switch (node.type) {
          case 'bold':
            return <strong key={i}>{node.content}</strong>
          case 'italic':
            return <em key={i}>{node.content}</em>
          case 'strike':
            return <s key={i}>{node.content}</s>
          case 'code':
            return (
              <code key={i} className="rounded bg-gray-100 px-1 font-mono text-sm">
                {node.content}
              </code>
            )
          case 'link':
            // 계약: http/https 만. 파서가 그 외 scheme 은 link 로 만들지 않는다.
            return (
              <a key={i} href={node.href} target="_blank" rel="noreferrer" className="underline">
                {node.content}
              </a>
            )
          case 'br':
            return <br key={i} />
          default:
            return <Fragment key={i}>{node.content}</Fragment>
        }
      })}
    </>
  )
}
