import { useEffect, useMemo, useRef, useState } from 'react'
import type { TemplateItem } from '../../api/templates'
import { TemplateDialog } from './TemplateDialog'
import { useTemplates } from './useTemplates'

const ALL = '全部'

export function TemplateGallery({ canClone }: { canClone: boolean }) {
  const { state, retry } = useTemplates('GAME')
  const [category, setCategory] = useState(ALL)
  const [active, setActive] = useState<TemplateItem | null>(null)
  const triggerRef = useRef<HTMLButtonElement | null>(null)

  const items = state.status === 'ready' ? state.items : []
  const categories = useMemo(() => [ALL, ...Array.from(new Set(items.map((item) => item.category)))], [items])
  const visible = category === ALL ? items : items.filter((item) => item.category === category)
  const deepLinkOpened = useRef(false)

  // A shareable link can open one example directly: /?type=GAME&template=star-swarm
  useEffect(() => {
    if (deepLinkOpened.current || state.status !== 'ready') return
    const slug = new URLSearchParams(window.location.search).get('template')
    if (!slug) return
    const item = state.items.find((candidate) => candidate.slug === slug)
    if (item) {
      deepLinkOpened.current = true
      setActive(item)
    }
  }, [state])

  function close() {
    setActive(null)
    window.requestAnimationFrame(() => triggerRef.current?.focus())
  }

  return (
    <section className="template-section" aria-labelledby="template-title">
      <div className="section-heading">
        <h2 id="template-title">游戏示例</h2>
        <span className="template-count">{state.status === 'ready' ? `${items.length} 个可直接试玩的模板` : ''}</span>
      </div>

      {state.status === 'loading' && (
        <div className="template-grid" aria-hidden="true">
          {[0, 1, 2, 3].map((index) => <div key={index} className="template-card skeleton" />)}
        </div>
      )}

      {state.status === 'error' && (
        <div className="template-error" role="alert">
          <p>{state.message}</p>
          <button type="button" className="secondary-button" onClick={retry}>重新加载模板</button>
        </div>
      )}

      {state.status === 'ready' && items.length === 0 && (
        <p className="template-empty">还没有可用的游戏模板。</p>
      )}

      {state.status === 'ready' && items.length > 0 && (
        <>
          <div className="template-filters" role="group" aria-label="按分类筛选模板">
            {categories.map((name) => (
              <button key={name} type="button" aria-pressed={category === name}
                className={`filter-chip ${category === name ? 'active' : ''}`}
                onClick={() => setCategory(name)}>{name}</button>
            ))}
          </div>

          <div className="template-grid">
            {visible.map((item) => (
              <button key={item.id} type="button" className="template-card"
                aria-label={`试玩 ${item.name}，${item.category}`}
                onClick={(event) => { triggerRef.current = event.currentTarget; setActive(item) }}>
                <span className="template-cover">
                  {item.coverUrl
                    ? <img src={item.coverUrl} alt="" loading="lazy" decoding="async" />
                    : <span className="template-cover-fallback">{item.name}</span>}
                  <span className="template-play">试玩</span>
                </span>
                <span className="template-card-body">
                  <span className="template-card-title">
                    <strong>{item.name}</strong>
                    <em>{item.category}</em>
                  </span>
                  <span className="template-card-summary">{item.summary}</span>
                  <span className="template-card-meta">{item.author} · v{item.versionNo}</span>
                </span>
              </button>
            ))}
          </div>
        </>
      )}

      {active && <TemplateDialog item={active} canClone={canClone} onClose={close} />}
    </section>
  )
}
