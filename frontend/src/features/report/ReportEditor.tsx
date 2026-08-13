/**
 * 보고서 편집기 (이슈 #38).
 * A4 미리보기 위에서 블록을 직접 편집·추가·삭제·드래그하고 1.5초 뒤 자동 저장한다.
 */
import { useEffect, useReducer, useState } from 'react'
import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core'
import {
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { editorReducer, type EditorState } from './editorState'
import { BlockView } from './BlockView'
import { BlockEditor } from './BlockEditor'
import { ADDABLE_BLOCK_TYPES, createBlock, type AddableBlockType } from './newBlock'
import { REPORT_TEMPLATES, FALLBACK_TEMPLATE, findTemplate } from './templates'
import { useAutosave, type SaveStatus } from './useAutosave'
import { useImageUrls } from './useImageUrls'
import type { Report, ReportBlock, ReportSection } from '../../lib/contracts/types'
import './report-document.css'

export function ReportEditor({
  report,
  initialTemplateId,
}: {
  report: Report
  /** 생성 흐름(#36)에서 고른 템플릿. 저장된 값이 없을 때만 적용한다. */
  initialTemplateId?: string
}) {
  const [state, dispatch] = useReducer(editorReducer, report, (r): EditorState => {
    const chosen = r.templateId === null ? findTemplate(initialTemplateId ?? null) : null
    return {
      document: r.document,
      templateId: chosen?.id ?? r.templateId,
      templateVersion: chosen?.version ?? r.templateVersion,
      past: [],
      future: [],
    }
  })
  const [editingId, setEditingId] = useState<string | null>(null)
  const imageUrls = useImageUrls(report.projectId, state.document)

  const { status } = useAutosave({
    reportId: report.id,
    document: state.document,
    templateId: state.templateId,
    templateVersion: state.templateVersion,
    presentationSettings: (report.presentationSettings ?? {}) as Record<
      string,
      string | number | boolean | null
    >,
    initialVersion: report.version,
  })

  // 블록 편집 입력창 안에서는 브라우저 기본 undo 를 존중한다
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (!(e.metaKey || e.ctrlKey) || e.key.toLowerCase() !== 'z') return
      const target = e.target as HTMLElement
      if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA') return
      e.preventDefault()
      dispatch({ type: e.shiftKey ? 'redo' : 'undo' })
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  const onDragEnd = (event: DragEndEvent) => {
    const { active, over } = event
    if (!over || active.id === over.id) return

    const sectionIndex = state.document.sections.findIndex((s) => s.id === over.id)
    if (sectionIndex >= 0 && state.document.sections.some((s) => s.id === active.id)) {
      dispatch({ type: 'moveSection', sectionId: String(active.id), toIndex: sectionIndex })
      return
    }
    // 블록: 같은 섹션 안에서만 이동한다
    const section = state.document.sections.find((s) => s.blocks.some((b) => b.id === active.id))
    if (!section) return
    const toIndex = section.blocks.findIndex((b) => b.id === over.id)
    if (toIndex < 0) return
    dispatch({ type: 'moveBlock', sectionId: section.id, blockId: String(active.id), toIndex })
  }

  const template = findTemplate(state.templateId) ?? FALLBACK_TEMPLATE

  return (
    <main className="min-h-screen bg-gray-200 pb-16">
      <Toolbar
        state={state}
        status={status}
        onTemplate={(id) => {
          const t = findTemplate(id)
          if (t) dispatch({ type: 'setTemplate', templateId: t.id, templateVersion: t.version })
        }}
        onUndo={() => dispatch({ type: 'undo' })}
        onRedo={() => dispatch({ type: 'redo' })}
      />

      {status === 'conflict' && (
        <p role="alert" className="mx-auto mt-3 max-w-3xl rounded bg-red-100 p-3 text-sm">
          다른 곳에서 이 보고서가 수정되어 자동 저장을 멈췄습니다. 새로고침하면 최신 내용을
          불러옵니다. 지금 화면의 편집분은 저장되지 않습니다.
        </p>
      )}

      <div className="mt-6 overflow-x-auto px-4">
        <div className={`report-sheet ${template.className}`}>
          <h1>{state.document.metadata.title}</h1>
          <MetaLine metadata={state.document.metadata} />

          <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
            <SortableContext
              items={state.document.sections.map((s) => s.id)}
              strategy={verticalListSortingStrategy}
            >
              {state.document.sections.map((section) => (
                <SortableSection key={section.id} section={section}>
                  <SortableContext
                    items={section.blocks.map((b) => b.id)}
                    strategy={verticalListSortingStrategy}
                  >
                    {section.blocks.map((block) => (
                      <SortableBlock
                        key={block.id}
                        block={block}
                        imageUrl={block.type === 'image' ? imageUrls[block.fileId] : undefined}
                        editing={editingId === block.id}
                        onEdit={() => setEditingId(block.id)}
                        onApply={(next) => {
                          dispatch({ type: 'updateBlock', sectionId: section.id, block: next })
                          setEditingId(null)
                        }}
                        onCancel={() => setEditingId(null)}
                        onDelete={() =>
                          dispatch({
                            type: 'deleteBlock',
                            sectionId: section.id,
                            blockId: block.id,
                          })
                        }
                      />
                    ))}
                  </SortableContext>
                  <AddBlockMenu
                    onAdd={(type) =>
                      dispatch({
                        type: 'addBlock',
                        sectionId: section.id,
                        afterBlockId: section.blocks.at(-1)?.id ?? null,
                        block: createBlock(type),
                      })
                    }
                  />
                </SortableSection>
              ))}
            </SortableContext>
          </DndContext>
        </div>
      </div>
    </main>
  )
}

function Toolbar({
  state,
  status,
  onTemplate,
  onUndo,
  onRedo,
}: {
  state: EditorState
  status: SaveStatus
  onTemplate: (id: string) => void
  onUndo: () => void
  onRedo: () => void
}) {
  return (
    <div className="sticky top-0 z-10 flex items-center gap-3 border-b border-gray-300 bg-white px-4 py-2">
      <label className="flex items-center gap-2 text-sm">
        템플릿
        <select
          value={state.templateId ?? FALLBACK_TEMPLATE.id}
          onChange={(e) => onTemplate(e.target.value)}
          className="rounded border border-gray-300 px-2 py-1"
        >
          {REPORT_TEMPLATES.map((t) => (
            <option key={t.id} value={t.id}>
              {t.name}
            </option>
          ))}
        </select>
      </label>

      <button
        type="button"
        onClick={onUndo}
        disabled={state.past.length === 0}
        className="rounded border border-gray-300 px-2 py-1 text-sm disabled:opacity-40"
      >
        ↩ 실행 취소
      </button>
      <button
        type="button"
        onClick={onRedo}
        disabled={state.future.length === 0}
        className="rounded border border-gray-300 px-2 py-1 text-sm disabled:opacity-40"
      >
        ↪ 다시 실행
      </button>

      <span aria-live="polite" className="ml-auto text-sm text-gray-500">
        {status === 'saving' && '저장 중…'}
        {status === 'saved' && '저장됨'}
        {status === 'error' && '저장 실패 — 편집하면 다시 시도합니다'}
      </span>
    </div>
  )
}

function MetaLine({ metadata }: { metadata: Report['document']['metadata'] }) {
  const parts = [metadata.author, metadata.course, metadata.date].filter(Boolean)
  if (parts.length === 0) return null
  return <p className="rpt-meta">{parts.join(' · ')}</p>
}

function SortableSection({
  section,
  children,
}: {
  section: ReportSection
  children: React.ReactNode
}) {
  const { attributes, listeners, setNodeRef, transform, transition } = useSortable({
    id: section.id,
  })
  return (
    <section ref={setNodeRef} style={{ transform: CSS.Transform.toString(transform), transition }}>
      <h2 className="group">
        <button
          type="button"
          {...attributes}
          {...listeners}
          aria-label={`${section.title} 섹션 순서 변경`}
          className="mr-2 cursor-grab text-gray-400"
        >
          ⠿
        </button>
        {section.title}
      </h2>
      {children}
    </section>
  )
}

function SortableBlock({
  block,
  imageUrl,
  editing,
  onEdit,
  onApply,
  onCancel,
  onDelete,
}: {
  block: ReportBlock
  imageUrl?: string
  editing: boolean
  onEdit: () => void
  onApply: (block: ReportBlock) => void
  onCancel: () => void
  onDelete: () => void
}) {
  const { attributes, listeners, setNodeRef, transform, transition } = useSortable({
    id: block.id,
  })

  if (editing) {
    return <BlockEditor block={block} onApply={onApply} onCancel={onCancel} />
  }

  return (
    <div
      ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition }}
      className="group relative -mx-2 rounded px-2 hover:bg-blue-50/60"
    >
      <div className="absolute -left-16 top-0 hidden gap-1 group-hover:flex group-focus-within:flex">
        <button
          type="button"
          {...attributes}
          {...listeners}
          aria-label="블록 순서 변경"
          className="cursor-grab rounded border border-gray-300 bg-white px-1.5 text-sm text-gray-500"
        >
          ⠿
        </button>
        <button
          type="button"
          onClick={onDelete}
          aria-label="블록 삭제"
          className="rounded border border-gray-300 bg-white px-1.5 text-sm text-gray-500 hover:text-red-600"
        >
          ✕
        </button>
      </div>
      <button type="button" onClick={onEdit} className="block w-full cursor-text text-left">
        <BlockView block={block} imageUrl={imageUrl} />
      </button>
    </div>
  )
}

function AddBlockMenu({ onAdd }: { onAdd: (type: AddableBlockType) => void }) {
  const [open, setOpen] = useState(false)
  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="mb-2 w-full rounded border border-dashed border-gray-300 py-1 text-sm text-gray-400 hover:text-gray-600"
      >
        + 블록 추가
      </button>
    )
  }
  return (
    <div className="mb-2 flex flex-wrap gap-1">
      {ADDABLE_BLOCK_TYPES.map(({ type, label }) => (
        <button
          key={type}
          type="button"
          onClick={() => {
            onAdd(type)
            setOpen(false)
          }}
          className="rounded border border-gray-300 px-2 py-1 text-sm hover:bg-gray-50"
        >
          {label}
        </button>
      ))}
      <button
        type="button"
        onClick={() => setOpen(false)}
        className="rounded px-2 py-1 text-sm text-gray-400"
      >
        닫기
      </button>
    </div>
  )
}
