import { useRef } from 'react'
import type { ImportedTemplate } from '../../api/imports'
import { Icon } from '../../components/Icon'

export function SlideImportTile({ enabled, uploading, error, onUpload }: {
  enabled: boolean
  uploading: boolean
  error: string | null
  onUpload: (file: File) => Promise<ImportedTemplate | null>
}) {
  const inputRef = useRef<HTMLInputElement>(null)

  return (
    <article className="slide-import" aria-busy={uploading}>
      <button type="button" className="slide-import-drop" disabled={!enabled || uploading}
        onClick={() => inputRef.current?.click()}>
        <span className="slide-import-icon"><Icon name="plus" /></span>
        <strong>{uploading ? '正在读取文件…' : '导入模板'}</strong>
        <span>上传 .pptx 或 .pdf 文件</span>
        <small>PPTX 会读取文字大纲 · PDF 仅保存原文件</small>
      </button>
      <input ref={inputRef} type="file" accept=".pptx,.pdf" hidden
        onChange={(event) => {
          const file = event.target.files?.[0]
          event.target.value = ''
          if (file) void onUpload(file)
        }} />
      {error && <p className="slide-import-error" role="alert">{error}</p>}
    </article>
  )
}
