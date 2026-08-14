/**
 * 보고서 편집기 (이슈 #38).
 * A4 미리보기 위에서 블록을 직접 편집·추가·삭제·드래그하고 1.5초 뒤 자동 저장한다.
 */
import { useEffect, useReducer, useRef, useState } from 'react'
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
import { downloadExportPdf, isExportFinished, useExportStatus, useStartExport } from './exportApi'
import { useProjectFiles } from '../files/api'
import { toDisplayMessage } from '../../lib/api/errors'
import type { Report, ReportBlock, ReportDocument, ReportSection } from '../../lib/contracts/types'
import 'pretendard/dist/web/variable/pretendardvariable.css'
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
  const [editingMetadata, setEditingMetadata] = useState(false)
  const [metadataDraft, setMetadataDraft] = useState<ReportDocument['metadata']>(
    report.document.metadata,
  )
  const [editingSectionId, setEditingSectionId] = useState<string | null>(null)
  const [sectionTitleDraft, setSectionTitleDraft] = useState('')
  const imageUrls = useImageUrls(report.projectId, state.document)
  const files = useProjectFiles(report.projectId)
  const imageFiles = (files.data?.items ?? []).filter(isImageFile)

  // PDF 내보내기: 요청 → 폴링 → 완료 시 자동 다운로드 (한 번만)
  const startExport = useStartExport(report.id)
  const [exportId, setExportId] = useState<string | null>(null)
  const exportStatus = useExportStatus(exportId)
  const downloadedRef = useRef<string | null>(null)
  useEffect(() => {
    const data = exportStatus.data
    if (!data || data.status !== 'COMPLETED' || downloadedRef.current === data.exportId) return
    downloadedRef.current = data.exportId
    void downloadExportPdf(data.exportId, `${state.document.metadata.title}.pdf`).catch(() => {
      downloadedRef.current = null // 실패하면 버튼으로 다시 시도할 수 있게 되돌린다
    })
  }, [exportStatus.data, state.document.metadata.title])

  const { status, savedTemplateId, savedTemplateVersion, savedDocument } = useAutosave({
    reportId: report.id,
    document: state.document,
    templateId: state.templateId,
    templateVersion: state.templateVersion,
    presentationSettings: (report.presentationSettings ?? {}) as Record<
      string,
      string | number | boolean | null
    >,
    server: {
      document: report.document,
      templateId: report.templateId,
      templateVersion: report.templateVersion,
      version: report.version,
    },
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
    // 블록은 같은 섹션뿐 아니라 다른 섹션의 블록/섹션 빈 공간으로도 옮길 수 있다.
    const source = state.document.sections.find((s) => s.blocks.some((b) => b.id === active.id))
    const target = state.document.sections.find(
      (s) => s.id === over.id || s.blocks.some((b) => b.id === over.id),
    )
    if (!source || !target) return
    const toIndex =
      target.id === over.id
        ? target.blocks.length
        : target.blocks.findIndex((b) => b.id === over.id)
    if (toIndex < 0) return
    if (source.id === target.id) {
      dispatch({ type: 'moveBlock', sectionId: source.id, blockId: String(active.id), toIndex })
    } else {
      dispatch({
        type: 'moveBlockToSection',
        fromSectionId: source.id,
        toSectionId: target.id,
        blockId: String(active.id),
        toIndex,
      })
    }
  }

  const template = findTemplate(state.templateId) ?? FALLBACK_TEMPLATE

  return (
    <main className="min-h-screen bg-gray-200 pb-16">
      <Toolbar
        state={state}
        status={status}
        exportState={{
          pending:
            startExport.isPending ||
            (exportId !== null &&
              !exportStatus.error &&
              !exportStatus.timedOut &&
              !(exportStatus.data && isExportFinished(exportStatus.data.status))),
          // 서버에 저장 안 된 상태(템플릿·문서)로 내보내면 409 가 나거나 낡은 스냅샷이
          // PDF 가 된다. 저장이 따라잡을 때까지 잠근다.
          waitingSave:
            state.templateId !== savedTemplateId ||
            state.templateVersion !== savedTemplateVersion ||
            state.document !== savedDocument ||
            status === 'saving',
          error: exportError(startExport.error, exportStatus),
        }}
        onTemplate={(id) => {
          const t = findTemplate(id)
          if (t) dispatch({ type: 'setTemplate', templateId: t.id, templateVersion: t.version })
        }}
        onUndo={() => dispatch({ type: 'undo' })}
        onRedo={() => dispatch({ type: 'redo' })}
        onExport={() => {
          downloadedRef.current = null
          startExport.mutate(undefined, { onSuccess: ({ exportId: id }) => setExportId(id) })
        }}
      />

      {status === 'conflict' && (
        <p role="alert" className="mx-auto mt-3 max-w-3xl rounded bg-red-100 p-3 text-sm">
          다른 곳에서 이 보고서가 수정되어 자동 저장을 멈췄습니다. 새로고침하면 최신 내용을
          불러옵니다. 지금 화면의 편집분은 저장되지 않습니다.
        </p>
      )}

      <div className="mt-6 overflow-x-auto px-4">
        <div className={`report-sheet ${template.className}`}>
          {editingMetadata ? (
            <MetadataEditor
              metadata={metadataDraft}
              onChange={setMetadataDraft}
              onApply={() => {
                const title = metadataDraft.title.trim()
                if (!title) return
                dispatch({ type: 'updateMetadata', metadata: { ...metadataDraft, title } })
                setEditingMetadata(false)
              }}
              onCancel={() => setEditingMetadata(false)}
            />
          ) : (
            <div className="group relative">
              <h1>{state.document.metadata.title}</h1>
              <MetaLine metadata={state.document.metadata} />
              <button
                type="button"
                onClick={() => {
                  setMetadataDraft(state.document.metadata)
                  setEditingMetadata(true)
                }}
                className="absolute right-0 top-0 rounded border border-gray-300 bg-white px-2 py-1 text-xs opacity-0 group-hover:opacity-100 focus:opacity-100"
              >
                문서 정보 편집
              </button>
            </div>
          )}

          <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
            <SortableContext
              items={state.document.sections.map((s) => s.id)}
              strategy={verticalListSortingStrategy}
            >
              {state.document.sections.map((section) => (
                <SortableSection
                  key={section.id}
                  section={section}
                  editing={editingSectionId === section.id}
                  titleDraft={sectionTitleDraft}
                  onEditTitle={() => {
                    setSectionTitleDraft(section.title)
                    setEditingSectionId(section.id)
                  }}
                  onTitleChange={setSectionTitleDraft}
                  onApplyTitle={() => {
                    const title = sectionTitleDraft.trim()
                    if (!title) return
                    dispatch({ type: 'updateSectionTitle', sectionId: section.id, title })
                    setEditingSectionId(null)
                  }}
                  onCancelTitle={() => setEditingSectionId(null)}
                >
                  <SortableContext
                    items={section.blocks.map((b) => b.id)}
                    strategy={verticalListSortingStrategy}
                  >
                    {section.blocks.map((block) => (
                      <SortableBlock
                        key={block.id}
                        block={block}
                        imageUrl={block.type === 'image' ? imageUrls[block.fileId] : undefined}
                        imageFiles={imageFiles}
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

/** 내보내기 실패 원인을 한 문장으로. 요청 실패(409 등)와 생성 실패를 함께 다룬다. */
function exportError(
  startError: unknown,
  exportStatus: ReturnType<typeof useExportStatus>,
): string | null {
  if (startError) return toDisplayMessage(startError)
  if (exportStatus.timedOut) return 'PDF 생성이 너무 오래 걸립니다. 다시 시도해 주세요.'
  if (exportStatus.error) return toDisplayMessage(exportStatus.error)
  const data = exportStatus.data
  if (data?.status === 'FAILED') return data.failureMessage ?? 'PDF 생성에 실패했습니다.'
  if (data?.status === 'EXPIRED') return 'PDF 보관 기간이 지났습니다. 다시 시도해 주세요.'
  return null
}

function Toolbar({
  state,
  status,
  exportState,
  onTemplate,
  onUndo,
  onRedo,
  onExport,
}: {
  state: EditorState
  status: SaveStatus
  exportState: { pending: boolean; waitingSave: boolean; error: string | null }
  onTemplate: (id: string) => void
  onUndo: () => void
  onRedo: () => void
  onExport: () => void
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

      {exportState.error && (
        <span role="alert" className="text-sm text-red-600">
          {exportState.error}
        </span>
      )}
      <button
        type="button"
        onClick={onExport}
        disabled={exportState.pending || exportState.waitingSave}
        title={exportState.waitingSave ? '변경 사항 저장 후 가능합니다' : undefined}
        className="rounded bg-gray-900 px-3 py-1 text-sm text-white disabled:opacity-40"
      >
        {exportState.pending ? 'PDF 만드는 중…' : 'PDF 다운로드'}
      </button>
    </div>
  )
}

function MetaLine({ metadata }: { metadata: Report['document']['metadata'] }) {
  const parts = [metadata.author, metadata.course, metadata.date].filter(Boolean)
  if (parts.length === 0) return null
  return <p className="rpt-meta">{parts.join(' · ')}</p>
}

function MetadataEditor({
  metadata,
  onChange,
  onApply,
  onCancel,
}: {
  metadata: ReportDocument['metadata']
  onChange: (metadata: ReportDocument['metadata']) => void
  onApply: () => void
  onCancel: () => void
}) {
  return (
    <div className="mb-4 space-y-2 rounded border border-blue-300 bg-blue-50/50 p-3">
      <h1 className="text-xl font-semibold">문서 정보 편집</h1>
      <label className="block text-sm">
        <span className="text-gray-600">제목 *</span>
        <input
          value={metadata.title}
          onChange={(event) => onChange({ ...metadata, title: event.target.value })}
          className="mt-0.5 w-full rounded border border-gray-300 px-2 py-1"
        />
      </label>
      {(['author', 'course', 'date'] as const).map((key) => (
        <label key={key} className="block text-sm">
          <span className="text-gray-600">
            {key === 'author' ? '작성자' : key === 'course' ? '과목' : '날짜'}
          </span>
          <input
            type={key === 'date' ? 'date' : 'text'}
            value={metadata[key] ?? ''}
            onChange={(event) => onChange({ ...metadata, [key]: event.target.value || undefined })}
            className="mt-0.5 w-full rounded border border-gray-300 px-2 py-1"
          />
        </label>
      ))}
      <div className="flex gap-2">
        <button
          type="button"
          onClick={onApply}
          className="rounded bg-gray-900 px-3 py-1 text-sm text-white"
        >
          확인
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="rounded border border-gray-300 px-3 py-1 text-sm"
        >
          취소
        </button>
      </div>
    </div>
  )
}

function SortableSection({
  section,
  children,
  editing,
  titleDraft,
  onEditTitle,
  onTitleChange,
  onApplyTitle,
  onCancelTitle,
}: {
  section: ReportSection
  children: React.ReactNode
  editing: boolean
  titleDraft: string
  onEditTitle: () => void
  onTitleChange: (title: string) => void
  onApplyTitle: () => void
  onCancelTitle: () => void
}) {
  const { attributes, listeners, setNodeRef, transform, transition } = useSortable({
    id: section.id,
  })
  return (
    <section ref={setNodeRef} style={{ transform: CSS.Transform.toString(transform), transition }}>
      <h2 className="group flex items-center gap-2">
        <button
          type="button"
          {...attributes}
          {...listeners}
          aria-label={`${section.title} 섹션 순서 변경`}
          className="mr-2 cursor-grab text-gray-400"
        >
          ⠿
        </button>
        {editing ? (
          <>
            <input
              aria-label="섹션 제목"
              value={titleDraft}
              onChange={(event) => onTitleChange(event.target.value)}
              className="min-w-0 flex-1 rounded border border-gray-300 px-2 py-1 text-lg font-semibold"
            />
            <button type="button" onClick={onApplyTitle} className="text-xs underline">
              확인
            </button>
            <button type="button" onClick={onCancelTitle} className="text-xs underline">
              취소
            </button>
          </>
        ) : (
          <>
            <span>{section.title}</span>
            <button
              type="button"
              onClick={onEditTitle}
              aria-label={`${section.title} 섹션 제목 편집`}
              className="text-xs text-gray-500 opacity-0 group-hover:opacity-100 focus:opacity-100"
            >
              편집
            </button>
          </>
        )}
      </h2>
      {children}
    </section>
  )
}

function SortableBlock({
  block,
  imageUrl,
  imageFiles,
  editing,
  onEdit,
  onApply,
  onCancel,
  onDelete,
}: {
  block: ReportBlock
  imageUrl?: string
  imageFiles: import('../../lib/contracts/types').FileResponse[]
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
    return (
      <BlockEditor block={block} imageFiles={imageFiles} onApply={onApply} onCancel={onCancel} />
    )
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

function isImageFile(file: import('../../lib/contracts/types').FileResponse): boolean {
  const mime = file.contentType.split(';')[0].trim().toLowerCase()
  return mime === 'image/png' || mime === 'image/jpeg' || /\.(png|jpe?g)$/i.test(file.originalName)
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
