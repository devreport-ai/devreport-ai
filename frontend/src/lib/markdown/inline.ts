/**
 * 계약이 허용한 인라인 마크다운 6종만 처리한다 (contracts/report-document.md).
 * 굵게 / 기울임 / 취소선 / inline code / http(s) 링크 / 줄바꿈.
 * raw HTML 금지 — 입력을 항상 이스케이프하므로 sanitize 를 겸한다.
 */

export interface InlineNode {
  type: 'text' | 'bold' | 'italic' | 'strike' | 'code' | 'link' | 'br'
  content?: string
  href?: string
}

/** `**굵게**` `*기울임*` `~~취소~~` `` `code` `` `[라벨](https://…)` 순서로 자른다. */
const TOKEN =
  /(\*\*(?<bold>[^*]+)\*\*)|(\*(?<italic>[^*]+)\*)|(~~(?<strike>[^~]+)~~)|(`(?<code>[^`]+)`)|(\[(?<label>[^\]]+)\]\((?<href>https?:\/\/[^\s)]+)\))/g

export function parseInline(text: string): InlineNode[] {
  const nodes: InlineNode[] = []

  // 줄바꿈은 토큰과 독립적으로 처리한다
  const lines = text.split('\n')
  lines.forEach((line, index) => {
    if (index > 0) nodes.push({ type: 'br' })
    parseLine(line, nodes)
  })

  return nodes
}

function parseLine(line: string, nodes: InlineNode[]): void {
  let last = 0
  for (const match of line.matchAll(TOKEN)) {
    if (match.index > last) nodes.push({ type: 'text', content: line.slice(last, match.index) })

    const g = match.groups ?? {}
    if (g.bold !== undefined) nodes.push({ type: 'bold', content: g.bold })
    else if (g.italic !== undefined) nodes.push({ type: 'italic', content: g.italic })
    else if (g.strike !== undefined) nodes.push({ type: 'strike', content: g.strike })
    else if (g.code !== undefined) nodes.push({ type: 'code', content: g.code })
    else if (g.label !== undefined) nodes.push({ type: 'link', content: g.label, href: g.href })

    last = match.index + match[0].length
  }
  if (last < line.length) nodes.push({ type: 'text', content: line.slice(last) })
}
