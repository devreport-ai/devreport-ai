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
import { BlockEditor } from './BlockEditor'
import { ReportDocumentView } from './ReportDocumentView'
import { ADDABLE_BLOCK_TYPES, createBlock, type AddableBlockType } from './newBlock'
import { REPORT_TEMPLATES, FALLBACK_TEMPLATE, findTemplate } from './templates'
import { useAutosave, type SaveStatus } from './useAutosave'
import { useImageUrls } from './useImageUrls'
import { downloadExportPdf, isExportFinished, useExportStatus, useStartExport } from './exportApi'
import { useAllProjectFiles } from '../files/api'
import { toDisplayMessage } from '../../lib/api/errors'
import type { Report, ReportBlock, ReportDocument, ReportSection } from '../../lib/contracts/types'
import { Link } from 'react-router'
import { BrandMark, Icon } from '../../components/ui'
import 'pretendard/dist/web/variable/pretendardvariable.css'
import './report-document.css'

const EMPTY_PRESENTATION_SETTINGS: Record<string, string | number | boolean | null> = {}

export function ReportEditor({
  report,
  initialTemplateId,
}: {
  report: Report
  /** 생성 흐름(#36)에서 고른 템플릿. 저장된 값이 없을 때만 적용한다. */
  initialTemplateId?: string
}) {
  // 좁은 화면에서는 편집·미리보기를 탭으로 전환한다 (#130). 넓은 화면에서는 CSS 가 둘 다 보여준다.
  const [mobileView, setMobileView] = useState<'edit' | 'preview'>('edit')
  const [state, dispatch] = useReducer(editorReducer, report, (r): EditorState => {
    const hasSavedTemplate = r.templateId !== null
    // 저장된 값도 생성 흐름 선택도 없으면 기본 템플릿을 쓴다.
    // null 로 두면 화면엔 기본 템플릿이 보이는데 서버에는 저장되지 않아 PDF 가 409 로 막힌다.
    // 저장된 id 가 목록에 없을 때는 건드리지 않는다 — 임의로 덮어쓰면 사용자 선택이 사라진다.
    const chosen =
      findTemplate(r.templateId ?? initialTemplateId ?? null) ??
      (hasSavedTemplate ? null : FALLBACK_TEMPLATE)
    return {
      document: r.document,
      templateId: chosen?.id ?? r.templateId,
      templateVersion: hasSavedTemplate
        ? r.templateVersion
        : (chosen?.version ?? r.templateVersion),
      past: [],
      future: [],
      historyGroup: null,
    }
  })
  const [editingId, setEditingId] = useState<string | null>(null)
  const [editingMetadata, setEditingMetadata] = useState(false)
  const [editingSectionId, setEditingSectionId] = useState<string | null>(null)
  const imageUrls = useImageUrls(report.projectId, state.document)
  const files = useAllProjectFiles(report.projectId)
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
    presentationSettings:
      (report.presentationSettings as Record<string, string | number | boolean | null> | null) ??
      EMPTY_PRESENTATION_SETTINGS,
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
    <main className="editor-page">
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
        mobileView={mobileView}
        onMobileView={setMobileView}
        onUndo={() => dispatch({ type: 'undo' })}
        onRedo={() => dispatch({ type: 'redo' })}
        onExport={() => {
          downloadedRef.current = null
          startExport.mutate(undefined, { onSuccess: ({ exportId: id }) => setExportId(id) })
        }}
      />

      {status === 'conflict' && (
        <p role="alert" className="editor-conflict">
          다른 곳에서 이 보고서가 수정되어 자동 저장을 멈췄습니다. 새로고침하면 최신 내용을
          불러옵니다. 지금 화면의 편집분은 저장되지 않습니다.
        </p>
      )}

      <div className={`editor-workspace editor-workspace--${mobileView}`}>
        <section
          className="editor-content-panel"
          id="editor-panel-edit"
          role="tabpanel"
          aria-labelledby="editor-tab-edit"
        >
          <header className="editor-panel-header">
            <div>
              <h1>보고서 콘텐츠</h1>
              <p>
                AI 초안이 HTML 템플릿 블록에 적용되었습니다. 내용을 수정하면 오른쪽 미리보기에 바로
                반영됩니다.
              </p>
            </div>
          </header>

          <div className="editor-info-card">
            {editingMetadata ? (
              <MetadataEditor
                metadata={state.document.metadata}
                onChange={(metadata) =>
                  dispatch({ type: 'updateMetadata', metadata, historyGroup: 'metadata' })
                }
                onClose={() => {
                  if (!state.document.metadata.title.trim()) return
                  dispatch({ type: 'endHistoryGroup' })
                  setEditingMetadata(false)
                }}
              />
            ) : (
              <>
                <div className="editor-info-card__header">
                  <div className="editor-info-card__icon" aria-hidden>
                    <Icon name="file-text" size={16} />
                  </div>
                  <div>
                    <strong>문서 정보</strong>
                    <p>
                      {state.document.metadata.title} · {state.document.metadata.author ?? '작성자'}
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => setEditingMetadata(true)}
                    aria-label="문서 정보 편집"
                    className="icon-button"
                  >
                    <Icon name="pencil" size={15} />
                  </button>
                </div>
                <div className="editor-info-card__values">
                  <span>
                    <small>제목</small>
                    <b>{state.document.metadata.title}</b>
                  </span>
                  <span>
                    <small>작성일</small>
                    <b>{state.document.metadata.date ?? '—'}</b>
                  </span>
                </div>
              </>
            )}
          </div>

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
                  onEditTitle={() => setEditingSectionId(section.id)}
                  onTitleChange={(title) =>
                    dispatch({
                      type: 'updateSectionTitle',
                      sectionId: section.id,
                      title,
                      historyGroup: `section:${section.id}`,
                    })
                  }
                  onCloseTitle={() => {
                    if (!section.title.trim()) return
                    dispatch({ type: 'endHistoryGroup' })
                    setEditingSectionId(null)
                  }}
                >
                  <SortableContext
                    items={section.blocks.map((b) => b.id)}
                    strategy={verticalListSortingStrategy}
                  >
                    {section.blocks.map((block) => (
                      <SortableBlock
                        key={block.id}
                        block={block}
                        imageFiles={imageFiles}
                        editing={editingId === block.id}
                        onEdit={() => setEditingId(block.id)}
                        onChange={(next) => {
                          dispatch({
                            type: 'updateBlock',
                            sectionId: section.id,
                            block: next,
                            historyGroup: `block:${block.id}`,
                          })
                        }}
                        onClose={() => {
                          if (block.type === 'image' && !block.alt.trim()) return
                          dispatch({ type: 'endHistoryGroup' })
                          setEditingId(null)
                        }}
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
        </section>

        <section
          className="editor-preview-panel"
          id="editor-panel-preview"
          role="tabpanel"
          aria-labelledby="editor-tab-preview"
        >
          <header className="editor-preview-header">
            <h2>실시간 미리보기</h2>
          </header>
          <div className="editor-preview-frame">
            <ReportDocumentView
              document={state.document}
              template={template}
              imageUrls={imageUrls}
              className="editor-preview-sheet"
              onEditBlock={(block) => setEditingId(block.id)}
            />
          </div>
        </section>
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
  mobileView,
  onMobileView,
  onTemplate,
  onUndo,
  onRedo,
  onExport,
}: {
  state: EditorState
  status: SaveStatus
  exportState: { pending: boolean; waitingSave: boolean; error: string | null }
  mobileView: 'edit' | 'preview'
  onMobileView: (view: 'edit' | 'preview') => void
  onTemplate: (id: string) => void
  onUndo: () => void
  onRedo: () => void
  onExport: () => void
}) {
  return (
    <header className="editor-toolbar">
      {/* 편집 화면에는 사이드바가 없다 — 로고가 홈으로 나가는 유일한 통로다 */}
      <Link to="/" aria-label="DevReport AI 홈">
        <BrandMark compact />
      </Link>
      <div className="editor-tabs" role="tablist" aria-label="편집 화면 보기">
        {(['edit', 'preview'] as const).map((view) => (
          <button
            key={view}
            type="button"
            role="tab"
            id={`editor-tab-${view}`}
            aria-selected={mobileView === view}
            aria-controls={`editor-panel-${view}`}
            onClick={() => onMobileView(view)}
            className={`editor-tab${mobileView === view ? ' editor-tab--active' : ''}`}
          >
            {view === 'edit' ? '콘텐츠' : '미리보기'}
          </button>
        ))}
      </div>
      <span className="editor-toolbar__spacer" />
      <span aria-live="polite" className="editor-save-status">
        <span
          className={
            status === 'saved'
              ? 'editor-save-status__dot editor-save-status__dot--saved'
              : 'editor-save-status__dot'
          }
        />
        {status === 'saving' && '저장 중…'}
        {status === 'saved' && '저장됨'}
        {status === 'error' && '저장 실패'}
        {status === 'invalid' && '필수값 확인'}
        {status === 'conflict' && '충돌 발생'}
      </span>
      <label className="editor-template-select">
        <span className="sr-only">템플릿</span>
        <select
          value={state.templateId ?? FALLBACK_TEMPLATE.id}
          onChange={(e) => onTemplate(e.target.value)}
          aria-label="템플릿"
        >
          {REPORT_TEMPLATES.map((t) => (
            <option key={t.id} value={t.id}>
              {t.galleryName} · HTML
            </option>
          ))}
        </select>
        <Icon name="chevron-down" size={14} />
      </label>

      <button
        type="button"
        onClick={onUndo}
        disabled={state.past.length === 0}
        className="editor-icon-action"
        aria-label="실행 취소"
        title="실행 취소"
      >
        <Icon name="undo" size={17} />
      </button>
      <button
        type="button"
        onClick={onRedo}
        disabled={state.future.length === 0}
        className="editor-icon-action"
        aria-label="다시 실행"
        title="다시 실행"
      >
        <Icon name="redo" size={17} />
      </button>

      {exportState.error && (
        <span role="alert" className="editor-export-error">
          {exportState.error}
        </span>
      )}
      <button
        type="button"
        onClick={onExport}
        disabled={exportState.pending || exportState.waitingSave}
        title={exportState.waitingSave ? '변경 사항 저장 후 가능합니다' : undefined}
        className="primary-button editor-export-button"
      >
        <Icon name="download" size={15} />
        {exportState.pending ? 'PDF 만드는 중…' : 'PDF 다운로드'}
      </button>
    </header>
  )
}

function MetadataEditor({
  metadata,
  onChange,
  onClose,
}: {
  metadata: ReportDocument['metadata']
  onChange: (metadata: ReportDocument['metadata']) => void
  onClose: () => void
}) {
  return (
    <div className="mb-4 space-y-2 rounded border border-blue-300 bg-blue-50/50 p-3">
      <h1 className="text-xl font-semibold">문서 정보 편집</h1>
      <label className="block text-sm">
        <span className="text-gray-600">제목 *</span>
        <input
          value={metadata.title}
          required
          aria-invalid={!metadata.title.trim()}
          onChange={(event) => onChange({ ...metadata, title: event.target.value })}
          className="mt-0.5 w-full rounded border border-gray-300 px-2 py-1"
        />
      </label>
      {!metadata.title.trim() && (
        <p className="inline-alert" role="alert">
          제목을 입력해 주세요.
        </p>
      )}
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
          onClick={onClose}
          disabled={!metadata.title.trim()}
          className="rounded bg-gray-900 px-3 py-1 text-sm text-white"
        >
          편집 종료
        </button>
      </div>
    </div>
  )
}

function SortableSection({
  section,
  children,
  editing,
  onEditTitle,
  onTitleChange,
  onCloseTitle,
}: {
  section: ReportSection
  children: React.ReactNode
  editing: boolean
  onEditTitle: () => void
  onTitleChange: (title: string) => void
  onCloseTitle: () => void
}) {
  const { attributes, listeners, setNodeRef, transform, transition } = useSortable({
    id: section.id,
  })
  return (
    <section
      ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition }}
      className="editor-section-card"
    >
      <h2 className="editor-section-header group">
        <button
          type="button"
          {...attributes}
          {...listeners}
          aria-label={`${section.title} 섹션 순서 변경`}
          className="editor-drag-handle"
        >
          <Icon name="grip" size={16} />
        </button>
        {editing ? (
          <>
            <input
              aria-label="섹션 제목"
              value={section.title}
              required
              aria-invalid={!section.title.trim()}
              onChange={(event) => onTitleChange(event.target.value)}
              className="field-control editor-section-title-input"
            />
            <button
              type="button"
              onClick={onCloseTitle}
              disabled={!section.title.trim()}
              className="text-xs underline"
            >
              편집 종료
            </button>
            {!section.title.trim() && (
              <span className="inline-alert" role="alert">
                제목을 입력해 주세요.
              </span>
            )}
          </>
        ) : (
          <>
            <span className="editor-section-title">{section.title}</span>
            <button
              type="button"
              onClick={onEditTitle}
              aria-label={`${section.title} 섹션 제목 편집`}
              className="editor-section-rename"
            >
              <Icon name="pencil" size={14} /> 제목 편집
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
  imageFiles,
  editing,
  onEdit,
  onChange,
  onClose,
  onDelete,
}: {
  block: ReportBlock
  imageFiles: import('../../lib/contracts/types').FileResponse[]
  editing: boolean
  onEdit: () => void
  onChange: (block: ReportBlock) => void
  onClose: () => void
  onDelete: () => void
}) {
  const { attributes, listeners, setNodeRef, transform, transition } = useSortable({
    id: block.id,
  })

  if (editing) {
    return (
      <BlockEditor block={block} imageFiles={imageFiles} onChange={onChange} onClose={onClose} />
    )
  }

  return (
    <div
      ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition }}
      className="editor-block-row"
    >
      <button
        type="button"
        {...attributes}
        {...listeners}
        aria-label="블록 순서 변경"
        className="editor-drag-handle"
      >
        <Icon name="grip" size={16} />
      </button>
      <span className="editor-block-icon" aria-hidden>
        <Icon name={blockIcon(block.type)} size={15} />
      </span>
      <button type="button" onClick={onEdit} className="editor-block-trigger">
        <strong>{blockLabel(block.type)}</strong>
        <span className="editor-block-summary">편집 가능한 콘텐츠 블록</span>
      </button>
      <button type="button" onClick={onDelete} aria-label="블록 삭제" className="editor-row-action">
        <Icon name="trash" size={15} />
      </button>
    </div>
  )
}

function blockLabel(type: ReportBlock['type']): string {
  return {
    paragraph: '문단',
    bulletList: '목록',
    code: '코드',
    table: '표',
    image: '이미지',
    callout: '콜아웃',
    pageBreak: '페이지 나누기',
  }[type]
}

function blockIcon(type: ReportBlock['type']): import('../../components/ui').IconName {
  return type === 'code'
    ? 'file-text'
    : type === 'image'
      ? 'eye'
      : type === 'callout'
        ? 'info'
        : 'file-text'
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
