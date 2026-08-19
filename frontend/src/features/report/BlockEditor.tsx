/**
 * 블록 편집 폼 — 클릭한 블록을 그 자리에서 textarea/입력칸으로 고친다.
 * 원문(인라인 마크다운 포함)을 그대로 편집하고, 입력 즉시 미리보기에 반영한다.
 */
import type { FileResponse, ReportBlock } from '../../lib/contracts/types'

export function BlockEditor({
  block,
  onChange,
  onClose,
  imageFiles = [],
}: {
  block: ReportBlock
  onChange: (block: ReportBlock) => void
  onClose: () => void
  imageFiles?: FileResponse[]
}) {
  return (
    <div className="space-y-2 rounded border border-blue-400 bg-blue-50/50 p-2">
      <Fields draft={block} onChange={onChange} imageFiles={imageFiles} />
      <div className="flex gap-2">
        <button
          type="button"
          onClick={onClose}
          className="rounded bg-gray-900 px-3 py-1 text-sm text-white"
        >
          편집 종료
        </button>
      </div>
    </div>
  )
}

function Fields({
  draft,
  onChange,
  imageFiles,
}: {
  draft: ReportBlock
  onChange: (block: ReportBlock) => void
  imageFiles: FileResponse[]
}) {
  switch (draft.type) {
    case 'paragraph':
      return (
        <Textarea
          label="내용"
          value={draft.content}
          onChange={(content) => onChange({ ...draft, content })}
        />
      )
    case 'bulletList':
      return (
        <Textarea
          label="항목 (한 줄에 하나)"
          value={draft.items.join('\n')}
          onChange={(text) => onChange({ ...draft, items: text.split('\n') })}
        />
      )
    case 'code':
      return (
        <>
          <Input
            label="언어"
            value={draft.language ?? ''}
            onChange={(language) => onChange({ ...draft, language: language || undefined })}
          />
          <Textarea
            label="코드"
            mono
            value={draft.code}
            onChange={(code) => onChange({ ...draft, code })}
          />
        </>
      )
    case 'table':
      return <TableFields draft={draft} onChange={onChange} />
    case 'image':
      return (
        <>
          <Input
            label="대체 텍스트"
            value={draft.alt}
            required
            onChange={(alt) => {
              if (alt !== '') onChange({ ...draft, alt })
            }}
          />
          <label className="block text-sm">
            <span className="text-gray-600">이미지 파일</span>
            <select
              value={draft.fileId}
              onChange={(event) => onChange({ ...draft, fileId: event.target.value })}
              className="mt-0.5 w-full rounded border border-gray-300 px-2 py-1"
            >
              {!imageFiles.some((file) => file.id === draft.fileId) && (
                <option value={draft.fileId}>현재 이미지 ({draft.fileId.slice(0, 8)})</option>
              )}
              {imageFiles.map((file) => (
                <option key={file.id} value={file.id}>
                  {file.originalName}
                </option>
              ))}
            </select>
          </label>
          <Input
            label="캡션"
            value={draft.caption ?? ''}
            onChange={(caption) => onChange({ ...draft, caption: caption || undefined })}
          />
        </>
      )
    case 'callout':
      return (
        <>
          <Input
            label="제목"
            value={draft.title ?? ''}
            onChange={(title) => onChange({ ...draft, title: title || undefined })}
          />
          <Textarea
            label="내용"
            value={draft.content}
            onChange={(content) => onChange({ ...draft, content })}
          />
        </>
      )
    case 'pageBreak':
      return <p className="text-sm text-gray-500">페이지 나누기는 편집할 내용이 없습니다.</p>
  }
}

function TableFields({
  draft,
  onChange,
}: {
  draft: Extract<ReportBlock, { type: 'table' }>
  onChange: (block: ReportBlock) => void
}) {
  return (
    <div className="space-y-1 overflow-x-auto">
      <div className="flex gap-1">
        {draft.columns.map((col, c) => (
          <input
            key={c}
            aria-label={`열 제목 ${c + 1}`}
            value={col}
            onChange={(e) => onChange({ ...draft, columns: draft.columns.with(c, e.target.value) })}
            className="w-32 rounded border border-gray-300 px-2 py-1 text-sm font-semibold"
          />
        ))}
      </div>
      {draft.rows.map((row, r) => (
        <div key={r} className="flex gap-1">
          {row.map((cell, c) => (
            <input
              key={c}
              aria-label={`${r + 1}행 ${c + 1}열`}
              value={cell}
              onChange={(e) =>
                onChange({ ...draft, rows: draft.rows.with(r, row.with(c, e.target.value)) })
              }
              className="w-32 rounded border border-gray-300 px-2 py-1 text-sm"
            />
          ))}
        </div>
      ))}
      <div className="flex flex-wrap gap-1 pt-1">
        <button
          type="button"
          onClick={() => onChange({ ...draft, rows: [...draft.rows, draft.columns.map(() => '')] })}
          className="rounded border border-gray-300 px-2 py-1 text-xs"
        >
          + 행
        </button>
        <button
          type="button"
          onClick={() => onChange({ ...draft, rows: draft.rows.slice(0, -1) })}
          disabled={draft.rows.length === 0}
          className="rounded border border-gray-300 px-2 py-1 text-xs disabled:opacity-40"
        >
          행 삭제
        </button>
        <button
          type="button"
          onClick={() =>
            onChange({
              ...draft,
              columns: [...draft.columns, '새 열'],
              rows: draft.rows.map((row) => [...row, '']),
            })
          }
          className="rounded border border-gray-300 px-2 py-1 text-xs"
        >
          + 열
        </button>
        <button
          type="button"
          onClick={() =>
            onChange({
              ...draft,
              columns: draft.columns.slice(0, -1),
              rows: draft.rows.map((row) => row.slice(0, -1)),
            })
          }
          disabled={draft.columns.length <= 1}
          className="rounded border border-gray-300 px-2 py-1 text-xs disabled:opacity-40"
        >
          열 삭제
        </button>
      </div>
    </div>
  )
}

function Input({
  label,
  value,
  required,
  onChange,
}: {
  label: string
  value: string
  required?: boolean
  onChange: (value: string) => void
}) {
  return (
    <label className="block text-sm">
      <span className="text-gray-600">{label}</span>
      <input
        value={value}
        required={required}
        onChange={(e) => onChange(e.target.value)}
        className="mt-0.5 w-full rounded border border-gray-300 px-2 py-1"
      />
    </label>
  )
}

function Textarea({
  label,
  value,
  mono,
  onChange,
}: {
  label: string
  value: string
  mono?: boolean
  onChange: (value: string) => void
}) {
  return (
    <label className="block text-sm">
      <span className="text-gray-600">{label}</span>
      <textarea
        value={value}
        rows={Math.min(12, Math.max(3, value.split('\n').length + 1))}
        onChange={(e) => onChange(e.target.value)}
        className={`mt-0.5 w-full rounded border border-gray-300 px-2 py-1 ${mono ? 'font-mono text-xs' : ''}`}
      />
    </label>
  )
}
