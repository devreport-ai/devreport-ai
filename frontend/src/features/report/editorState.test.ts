/** 편집기 상태 테스트 — undo 가 틀리면 사용자가 작업을 잃는다. */
import { describe, expect, it } from 'vitest'
import { editorReducer, newBlockId, type EditorState } from './editorState'
import type { ReportBlock, ReportDocument } from '../../lib/contracts/types'

function doc(blockIds: string[]): ReportDocument {
  return {
    metadata: { title: '제목' },
    sections: [
      {
        id: 'sec-1',
        title: '섹션',
        blocks: blockIds.map((id) => ({ id, type: 'paragraph', content: `내용-${id}` })),
      },
      { id: 'sec-2', title: '둘째', blocks: [] },
    ],
  }
}

function state(blockIds: string[] = ['a', 'b', 'c']): EditorState {
  return { document: doc(blockIds), templateId: null, templateVersion: null, past: [], future: [] }
}

const para = (id: string, content: string): ReportBlock => ({ id, type: 'paragraph', content })

describe('editorReducer', () => {
  it('블록을 삭제하고 Ctrl+Z 로 되돌린다', () => {
    const s0 = state()
    const s1 = editorReducer(s0, { type: 'deleteBlock', sectionId: 'sec-1', blockId: 'b' })
    expect(s1.document.sections[0].blocks.map((b) => b.id)).toEqual(['a', 'c'])

    const s2 = editorReducer(s1, { type: 'undo' })
    expect(s2.document.sections[0].blocks.map((b) => b.id)).toEqual(['a', 'b', 'c'])
  })

  it('redo 로 되돌린 것을 다시 적용한다', () => {
    const s1 = editorReducer(state(), { type: 'deleteBlock', sectionId: 'sec-1', blockId: 'b' })
    const s2 = editorReducer(s1, { type: 'undo' })
    const s3 = editorReducer(s2, { type: 'redo' })
    expect(s3.document.sections[0].blocks.map((b) => b.id)).toEqual(['a', 'c'])
  })

  it('undo 후 새 편집을 하면 redo 갈래가 사라진다', () => {
    const s1 = editorReducer(state(), { type: 'deleteBlock', sectionId: 'sec-1', blockId: 'b' })
    const s2 = editorReducer(s1, { type: 'undo' })
    const s3 = editorReducer(s2, {
      type: 'updateBlock',
      sectionId: 'sec-1',
      block: para('a', '수정'),
    })
    expect(s3.future).toEqual([])
  })

  it('블록 수정이 히스토리에 쌓인다', () => {
    const s1 = editorReducer(state(), {
      type: 'updateBlock',
      sectionId: 'sec-1',
      block: para('a', '고침'),
    })
    expect((s1.document.sections[0].blocks[0] as { content: string }).content).toBe('고침')
    expect(s1.past).toHaveLength(1)
  })

  it('블록을 지정 위치 뒤에 추가한다', () => {
    const s1 = editorReducer(state(), {
      type: 'addBlock',
      sectionId: 'sec-1',
      afterBlockId: 'a',
      block: para('new', ''),
    })
    expect(s1.document.sections[0].blocks.map((b) => b.id)).toEqual(['a', 'new', 'b', 'c'])
  })

  it('블록 순서를 옮긴다', () => {
    const s1 = editorReducer(state(), {
      type: 'moveBlock',
      sectionId: 'sec-1',
      blockId: 'c',
      toIndex: 0,
    })
    expect(s1.document.sections[0].blocks.map((b) => b.id)).toEqual(['c', 'a', 'b'])
  })

  it('섹션 순서를 옮긴다', () => {
    const s1 = editorReducer(state(), { type: 'moveSection', sectionId: 'sec-2', toIndex: 0 })
    expect(s1.document.sections.map((s) => s.id)).toEqual(['sec-2', 'sec-1'])
  })

  it('템플릿 변경은 document 를 건드리지 않고 히스토리에도 안 쌓인다', () => {
    // 계약: 템플릿 변경은 document 를 수정하지 않는다
    const s0 = state()
    const s1 = editorReducer(s0, { type: 'setTemplate', templateId: 'compact', templateVersion: 1 })
    expect(s1.document).toBe(s0.document)
    expect(s1.past).toHaveLength(0)
    expect(s1.templateId).toBe('compact')
    expect(s1.templateVersion).toBe(1)
  })

  it('되돌릴 게 없으면 undo 는 아무것도 안 한다', () => {
    const s0 = state()
    expect(editorReducer(s0, { type: 'undo' })).toBe(s0)
  })
})

describe('newBlockId', () => {
  it('계약 stableId 패턴을 만족한다', () => {
    for (let i = 0; i < 20; i += 1) {
      expect(newBlockId()).toMatch(/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/)
    }
  })
})
