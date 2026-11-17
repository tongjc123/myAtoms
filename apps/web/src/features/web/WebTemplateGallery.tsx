import { useMemo, useState } from 'react'
import type { TemplateItem } from '../../api/templates'
import { useTemplates } from '../templates/useTemplates'
import { WebPreviewDialog } from './WebPreviewDialog'
import { WebTemplateCard } from './WebTemplateCard'

const ALL = '全部'

export function WebTemplateGallery({ canClone }: { canClone: boolean }) {
  const { state, retry } = useTemplates('WEB')
  const [category, setCategory] = useState(ALL)
  const [active, setActive] = useState<{ item: TemplateItem; focusName: boolean } | null>(null)

  const items = state.status === 'ready' ? state.items : []
  const categories = useMemo(() => [ALL, ...Array.from(new Set(items.map((item) => item.category)))], [items])
  const visible = category === ALL ? items : items.filter((item) => item.category === category)

  return (
    <section className="web-gallery" aria-labelledby="web-template-title">
      <div className="section-heading">
        <h2 id="web-template-title">网页模板</h2>
        <span className="template-count">
          {state.status === 'ready' ? `${items.length} 套可直接克隆的模板` : ''}
        </span>
      </div>

      {state.status === 'loading' && (
        <div className="web-grid" aria-hidden="true">
          {[0, 1, 2, 3].map((index) => <div key={index} className="web-card skeleton" />)}
        </div>
      )}

      {state.status === 'error' && (
        <div className="template-error" role="alert">
          <p>{state.message}</p>
          <button type="button" className="secondary-button" onClick={retry}>重新加载模板</button>
        </div>
      )}

      {state.status === 'ready' && items.length === 0 && (
        <p className="template-empty">还没有可用的网页模板。</p>
      )}

      {state.status === 'ready' && items.length > 0 && (
        <>
          <div className="template-filters" role="group" aria-label="按分类筛选网页模板">
            {categories.map((name) => (
              <button key={name} type="button" aria-pressed={category === name}
                className={`filter-chip ${category === name ? 'active' : ''}`}
                onClick={() => setCategory(name)}>{name}</button>
            ))}
          </div>

          <div className="web-grid">
            {visible.map((item) => (
              <WebTemplateCard key={item.id} item={item}
                onPreview={() => setActive({ item, focusName: false })}
                onUse={() => setActive({ item, focusName: true })} />
            ))}
          </div>
        </>
      )}

      <p className="web-gallery-note">
        每套模板都是一个可直接运行的单文件网页：悬停可以预览真实页面，点击进去还能操作表单与切换主题。
        使用模板会把当前版本复制成一个独立项目，网页生成流水线仍在规划中。
      </p>

      {active && (
        <WebPreviewDialog item={active.item} canClone={canClone} focusName={active.focusName}
          onClose={() => setActive(null)} />
      )}
    </section>
  )
}
