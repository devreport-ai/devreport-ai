/**
 * 파일 업로드 패널.
 *
 * 계약상 업로드 API 는 한 번에 파일 하나만 받는다. 여러 개를 고르면 이 컴포넌트가
 * 병렬로 호출하고 파일별 진행률·성공·실패를 따로 보여준다 (이슈 #36).
 *
 * 실패한 파일만 골라 다시 올릴 수 있어야 한다는 것도 완료 조건이다.
 * 자동 재시도는 하지 않는다. 20 MiB 파일을 사용자 동의 없이 다시 올리면
 * 데이터 요금과 대기 시간을 몰래 쓰는 셈이 된다.
 */
import { useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { fileKeys, uploadProjectFile } from './api'
import { toDisplayMessage } from '../../lib/api/errors'
import { Icon } from '../../components/ui'

/**
 * 파일 선택창에서 미리 걸러 줄 확장자.
 *
 * 업로드 계약과 AI 분석 입력 계약이 모두 허용하는 형식만 고를 수 있게 한다.
 * 서버 검증을 대체하지는 않는다.
 */
const ACCEPT = '.zip,.pdf,.md,.txt,.png,.jpg,.jpeg'

/** 계약상 파일당 상한. 서버가 413 으로 거절하기 전에 미리 알려 주면 기다림을 아낀다. */
const MAX_BYTES = 20 * 1024 * 1024

type UploadState = 'pending' | 'uploading' | 'done' | 'error'

interface UploadItem {
  /** 같은 이름 파일을 여러 번 고를 수 있으므로 파일명이 아니라 별도 키가 필요하다. */
  key: string
  file: File
  state: UploadState
  /** 0~1 */
  progress: number
  message?: string
}

let sequence = 0

export function UploadPanel({ projectId }: { projectId: string }) {
  const client = useQueryClient()
  const [items, setItems] = useState<UploadItem[]>([])
  const [dragging, setDragging] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  const update = (key: string, patch: Partial<UploadItem>) => {
    setItems((prev) => prev.map((item) => (item.key === key ? { ...item, ...patch } : item)))
  }

  /** 서버에 새 파일이 생겼으니 아래 파일 목록을 다시 부른다. */
  const refreshFileList = () => {
    void client.invalidateQueries({ queryKey: fileKeys.all(projectId) })
  }

  /** @returns 업로드 성공 여부. 호출부가 목록 갱신 여부를 판단하는 데 쓴다. */
  const runUpload = async (item: UploadItem): Promise<boolean> => {
    if (item.file.size > MAX_BYTES) {
      update(item.key, { state: 'error', message: '20 MiB 를 넘는 파일은 올릴 수 없습니다.' })
      return false
    }

    update(item.key, { state: 'uploading', progress: 0, message: undefined })
    try {
      await uploadProjectFile(projectId, item.file, {
        onProgress: (ratio) => update(item.key, { progress: ratio }),
      })
      update(item.key, { state: 'done', progress: 1 })
      return true
    } catch (error) {
      update(item.key, { state: 'error', message: toDisplayMessage(error) })
      return false
    }
  }

  const queueFiles = (picked: File[]) => {
    if (picked.length === 0) return

    const next = picked.map<UploadItem>((file) => ({
      key: `${Date.now()}-${sequence++}`,
      file,
      state: 'pending',
      progress: 0,
    }))
    setItems((prev) => [...prev, ...next])

    // 병렬 업로드. 하나가 실패해도 나머지는 계속 올라가야 하므로 allSettled 를 쓴다.
    void Promise.allSettled(next.map(runUpload)).then(refreshFileList)
  }

  const handleSelect = (event: React.ChangeEvent<HTMLInputElement>) => {
    queueFiles(Array.from(event.target.files ?? []))
    // 같은 파일을 다시 고를 수 있도록 input 을 비운다. 안 비우면 change 가 안 걸린다.
    event.target.value = ''
  }

  const handleDrop = (event: React.DragEvent<HTMLLabelElement>) => {
    event.preventDefault()
    setDragging(false)
    queueFiles(Array.from(event.dataTransfer.files))
  }

  const completed = items.filter((i) => i.state === 'done').length
  const failed = items.filter((i) => i.state === 'error')

  return (
    <div>
      <div className="detail-card__header">
        <div>
          <h2>파일 업로드</h2>
          <p className="inline-hint">분석에 필요한 자료를 추가하세요.</p>
        </div>
      </div>

      <label
        htmlFor="file-input"
        className={dragging ? 'upload-dropzone upload-dropzone--dragging' : 'upload-dropzone'}
        onDragEnter={(event) => {
          event.preventDefault()
          setDragging(true)
        }}
        onDragOver={(event) => event.preventDefault()}
        onDragLeave={(event) => {
          if (event.currentTarget === event.target) setDragging(false)
        }}
        onDrop={handleDrop}
      >
        <span className="upload-dropzone__icon" aria-hidden>
          <Icon name="upload" size={17} />
        </span>
        <strong>
          파일 선택 <span>또는 여기에 파일을 놓아 주세요</span>
        </strong>
        <span>ZIP · PDF · MD · TXT · PNG · JPG, 파일당 20 MiB 까지.</span>
      </label>
      <input
        id="file-input"
        ref={inputRef}
        type="file"
        multiple
        accept={ACCEPT}
        onChange={handleSelect}
        className="sr-only"
      />

      {items.length > 0 && (
        <>
          <p className="inline-hint">
            {completed} / {items.length} 완료
            {failed.length > 0 && <span className="text-red-600"> · {failed.length}개 실패</span>}
          </p>

          <ul className="upload-items">
            {items.map((item) => (
              <li key={item.key} className="upload-item">
                <div className="upload-item__heading">
                  <span className="upload-item__name">{item.file.name}</span>
                  <span className="upload-item__state">
                    {item.state === 'uploading' && `${Math.round(item.progress * 100)}%`}
                    {item.state === 'done' && '완료'}
                    {item.state === 'pending' && '대기'}
                    {item.state === 'error' && <span className="field-error">실패</span>}
                  </span>
                </div>

                {item.state === 'uploading' && (
                  <progress
                    className="upload-item__progress"
                    max={1}
                    value={item.progress}
                    aria-label={`${item.file.name} 업로드 진행률`}
                  />
                )}

                {item.state === 'error' && (
                  <div className="upload-item__heading">
                    <span role="alert" className="field-error">
                      {item.message}
                    </span>
                    <button
                      type="button"
                      onClick={() =>
                        void runUpload(item).then((ok) => {
                          if (ok) refreshFileList()
                        })
                      }
                      className="secondary-button"
                    >
                      다시 시도
                    </button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  )
}
