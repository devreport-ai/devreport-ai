/**
 * 편집기 상태 — 문서를 불변으로 다루고, 바뀔 때마다 이전 상태를 스택에 쌓는다.
 * 그래서 삭제·이동·수정 무엇이든 Ctrl+Z 한 가지로 되돌린다.
 *
 * 계약 준수: 템플릿 변경은 document 를 건드리지 않는다 (envelope 필드만).
 */
import type { ReportBlock, ReportDocument, ReportSection } from '../../lib/contracts/types'

export interface EditorState {
  document: ReportDocument
  /** null·null 이거나 둘 다 값 — 계약의 oneOf 규칙 */
  templateId: string | null
  templateVersion: number | null
  past: ReportDocument[]
  future: ReportDocument[]
}

export type EditorAction =
  | { type: 'updateMetadata'; metadata: ReportDocument['metadata'] }
  | { type: 'updateSectionTitle'; sectionId: string; title: string }
  | { type: 'updateBlock'; sectionId: string; block: ReportBlock }
  | { type: 'addBlock'; sectionId: string; afterBlockId: string | null; block: ReportBlock }
  | { type: 'deleteBlock'; sectionId: string; blockId: string }
  | { type: 'moveBlock'; sectionId: string; blockId: string; toIndex: number }
  | {
      type: 'moveBlockToSection'
      fromSectionId: string
      toSectionId: string
      blockId: string
      toIndex: number
    }
  | { type: 'moveSection'; sectionId: string; toIndex: number }
  | { type: 'setTemplate'; templateId: string; templateVersion: number }
  | { type: 'undo' }
  | { type: 'redo' }

const HISTORY_LIMIT = 100

export function editorReducer(state: EditorState, action: EditorAction): EditorState {
  switch (action.type) {
    case 'undo': {
      const previous = state.past.at(-1)
      if (!previous) return state
      return {
        ...state,
        document: previous,
        past: state.past.slice(0, -1),
        future: [state.document, ...state.future],
      }
    }
    case 'redo': {
      const [next, ...rest] = state.future
      if (!next) return state
      return { ...state, document: next, past: [...state.past, state.document], future: rest }
    }
    case 'setTemplate':
      // document 가 아니라 envelope 값이라 히스토리에 안 쌓는다 (Ctrl+Z 대상 아님)
      return { ...state, templateId: action.templateId, templateVersion: action.templateVersion }
    default:
      return pushHistory(state, applyDocumentAction(state.document, action))
  }
}

function pushHistory(state: EditorState, document: ReportDocument): EditorState {
  if (document === state.document) return state
  return {
    ...state,
    document,
    past: [...state.past, state.document].slice(-HISTORY_LIMIT),
    future: [], // 새 편집을 하면 redo 갈래는 사라진다
  }
}

function applyDocumentAction(
  document: ReportDocument,
  action: Exclude<EditorAction, { type: 'undo' | 'redo' | 'setTemplate' }>,
): ReportDocument {
  switch (action.type) {
    case 'updateMetadata':
      return { ...document, metadata: action.metadata }
    case 'updateSectionTitle':
      return mapSection(document, action.sectionId, (section) => ({
        ...section,
        title: action.title,
      }))
    case 'updateBlock':
      return mapSection(document, action.sectionId, (section) => ({
        ...section,
        blocks: section.blocks.map((b) => (b.id === action.block.id ? action.block : b)),
      }))
    case 'addBlock':
      return mapSection(document, action.sectionId, (section) => {
        const at =
          action.afterBlockId === null
            ? section.blocks.length
            : section.blocks.findIndex((b) => b.id === action.afterBlockId) + 1
        return { ...section, blocks: section.blocks.toSpliced(at, 0, action.block) }
      })
    case 'deleteBlock':
      return mapSection(document, action.sectionId, (section) => ({
        ...section,
        blocks: section.blocks.filter((b) => b.id !== action.blockId),
      }))
    case 'moveBlock':
      return mapSection(document, action.sectionId, (section) => {
        const from = section.blocks.findIndex((b) => b.id === action.blockId)
        if (from < 0 || from === action.toIndex) return section
        const blocks = [...section.blocks]
        const [moved] = blocks.splice(from, 1)
        blocks.splice(action.toIndex, 0, moved)
        return { ...section, blocks }
      })
    case 'moveBlockToSection': {
      if (action.fromSectionId === action.toSectionId) return document
      const source = document.sections.find((s) => s.id === action.fromSectionId)
      const target = document.sections.find((s) => s.id === action.toSectionId)
      if (!source || !target) return document
      const from = source.blocks.findIndex((b) => b.id === action.blockId)
      if (from < 0) return document
      const moved = source.blocks[from]
      if (!moved) return document
      return {
        ...document,
        sections: document.sections.map((section) => {
          if (section.id === source.id)
            return { ...section, blocks: source.blocks.toSpliced(from, 1) }
          if (section.id === target.id) {
            const blocks = [...section.blocks]
            blocks.splice(Math.max(0, Math.min(action.toIndex, blocks.length)), 0, moved)
            return { ...section, blocks }
          }
          return section
        }),
      }
    }
    case 'moveSection': {
      const from = document.sections.findIndex((s) => s.id === action.sectionId)
      if (from < 0 || from === action.toIndex) return document
      const sections = [...document.sections]
      const [moved] = sections.splice(from, 1)
      sections.splice(action.toIndex, 0, moved)
      return { ...document, sections }
    }
  }
}

function mapSection(
  document: ReportDocument,
  sectionId: string,
  fn: (section: ReportSection) => ReportSection,
): ReportDocument {
  return {
    ...document,
    sections: document.sections.map((s) => (s.id === sectionId ? fn(s) : s)),
  }
}

/** 계약 stableId 규칙(^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$)에 맞는 새 블록 id. */
export function newBlockId(): string {
  return `b-${crypto.randomUUID()}` // 'b'+38자 — 영숫자 시작, 64자 이내
}
