import { useMemo, useState } from 'react'
import type { ImportedTemplate } from '../../api/imports'
import type { TemplateItem } from '../../api/templates'
import { SlideImportTile } from './SlideImportTile'
import { SlidePreviewDialog } from './SlidePreviewDialog'
import { SlideTemplateCard } from './SlideTemplateCard'
import { useTemplates } from '../templates/useTemplates'

const ALL = '全部'
const MINE = '我的模板'

export function SlideGallery({ enabled, selectedKey, imports, onUseTemplate, onUseImport }: {
  enabled: boolean
  selectedKey: string | null
  imports: {
    state: { status: 'loading' | 'ready' | 'error'; items: ImportedTemplate[]; message?: string }
    uploading: boolean
    error: string | null
    upload: (file: File) => Promise<ImportedTemplate | null>
    refresh: () => void
  }
  onUseTemplate: (item: TemplateItem) => void
  onUseImport: (item: ImportedTemplate) => void
}) {
  const { state, retry } = useTemplates('SLIDES')
  const [category, setCategory] = useState(ALL)
  const [preview, setPreview] = useState<TemplateItem | null>(null)

  const items = state.status === 'ready' ? state.items : []
  const categories = useMemo(() => {
    const names = Array.from(new Set(items.map((item) => item.category)))
    return [ALL, MINE, ...names]
  }, [items])
  const visible = category === ALL ? items
    : category === MINE ? []
    : items.filter((item) => item.category === category)
  const imported = imports.state.items

  return (
    <section className="slide-gallery" aria-labelledby="slide-template-title">
      <div className="section-heading">
        <h2 id="slide-template-title">选择模板</h2>
        <span className="template-count">
          {state.status === 'ready' ? `${items.length} 套内置模板` : ''}
          {imported.length ? ` · 已导入 ${imported.length} 份` : ''}
        </span>
      </div>

      <div className="template-filters" role="group" aria-label="按分类筛选模板">
        {categories.map((name) => (
          <button key={name} type="button" aria-pressed={category === name}
            className={`filter-chip ${category === name ? 'active' : ''}`}
            onClick={() => setCategory(name)}>{name}</button>
        ))}
      </div>

      {state.status === 'error' && (
        <div className="template-error" role="alert">
          <p>{state.message}</p>
          <button type="button" className="secondary-button" onClick={retry}>重新加载模板</button>
        </div>
      )}

      <div className="slide-grid">
        {(category === ALL || category === MINE) && (
          <SlideImportTile enabled={enabled} uploading={imports.uploading} error={imports.error}
            onUpload={imports.upload} />
        )}

        {(category === ALL || category === MINE) && imported.map((item) => (
          <article key={item.id} className="slide-card is-import">
            <div className="slide-card-cover">
              <div className="slide-import-art">
                <span className="slide-import-badge">我的模板</span>
                <strong>{item.name}</strong>
                <span className="slide-import-file">{item.filename}</span>
                <span className="slide-cover-meta">{item.slideCount} 页 · {item.note}</span>
              </div>
              <button type="button" className="slide-use" onClick={() => onUseImport(item)}>
                {selectedKey === `import:${item.id}` ? '已引用' : '使用模板'}
              </button>
            </div>
            <div className="slide-card-body">
              <span className="slide-card-meta">{item.note}</span>
              {item.outlineTitles.length > 0 && (
                <p className="slide-card-summary">{item.outlineTitles.slice(0, 3).join(' / ')}</p>
              )}
            </div>
          </article>
        ))}

        {category !== MINE && visible.map((item) => (
          <SlideTemplateCard key={item.id} item={item} active={selectedKey === `template:${item.slug}`}
            onUse={() => onUseTemplate(item)} onPreview={() => setPreview(item)} />
        ))}
      </div>

      {state.status === 'loading' && (
        <div className="slide-grid" aria-hidden="true">
          {[0, 1, 2].map((index) => <div key={index} className="slide-card skeleton" />)}
        </div>
      )}

      {state.status === 'ready' && category === MINE && imported.length === 0 && (
        <p className="template-empty">还没有导入的模板。上传一份 PPTX 后，它会出现在这里并可以作为大纲使用。</p>
      )}

      {preview && (
        <SlidePreviewDialog item={preview} onClose={() => setPreview(null)}
          onUse={() => { onUseTemplate(preview); setPreview(null) }} />
      )}
    </section>
  )
}
