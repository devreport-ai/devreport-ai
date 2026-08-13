/** 블록 추가 메뉴가 쓰는 타입별 기본값. image 는 파일 선택이 필요해 후속으로 미룬다. */
import { newBlockId } from './editorState'
import type { ReportBlock } from '../../lib/contracts/types'

export const ADDABLE_BLOCK_TYPES = [
  { type: 'paragraph', label: '문단' },
  { type: 'bulletList', label: '목록' },
  { type: 'code', label: '코드' },
  { type: 'table', label: '표' },
  { type: 'callout', label: '콜아웃' },
  { type: 'pageBreak', label: '페이지 나누기' },
] as const

export type AddableBlockType = (typeof ADDABLE_BLOCK_TYPES)[number]['type']

export function createBlock(type: AddableBlockType): ReportBlock {
  const id = newBlockId()
  switch (type) {
    case 'paragraph':
      return { id, type, content: '' }
    case 'bulletList':
      return { id, type, items: [''] }
    case 'code':
      return { id, type, code: '' }
    case 'table':
      return { id, type, columns: ['항목', '값'], rows: [['', '']] }
    case 'callout':
      return { id, type, content: '' }
    case 'pageBreak':
      return { id, type }
  }
}
