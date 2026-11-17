import type { TemplateItem } from '../../api/templates'
import { useHoverPeek } from '../templates/useHoverPeek'

export function SlideTemplateCard({ item, active, onUse, onPreview }: {
  item: TemplateItem
  active: boolean
  onUse: () => void
  onPreview: () => void
}) {
  const peek = useHoverPeek()

  return (
    <article className={`slide-card ${active ? 'is-active' : ''}`} data-theme={item.theme ?? 'swiss'}
      onMouseEnter={peek.start} onMouseLeave={peek.stop}>
      <div className="slide-card-cover">
        <button type="button" className="slide-cover-btn" onClick={onPreview}
          onFocus={peek.start} onBlur={peek.stop}
          aria-label={`预览模板 ${item.name}`}>
          <span className="slide-cover-art" aria-hidden="true">
            <span className="slide-cover-kicker">{item.category}</span>
            <span className="slide-cover-name">{item.name}</span>
            <span className="slide-cover-rule" />
            <span className="slide-cover-meta">{item.slideCount ? `${item.slideCount} 页 · 16:9` : '16:9'}</span>
          </span>
          {peek.peeking && (
            <iframe className="slide-peek" title="" tabIndex={-1} loading="lazy"
              sandbox="allow-scripts" referrerPolicy="no-referrer"
              src={`${item.playUrl}?peek=1&slide=1`} />
          )}
          <span className="slide-cover-hint">点击预览</span>
        </button>
        <button type="button" className={`slide-use ${active ? 'active' : ''}`} onClick={onUse}>
          {active ? '已引用' : '使用模板'}
        </button>
      </div>
      <div className="slide-card-body">
        <span className="slide-card-meta">{item.category} · v{item.versionNo} · {item.slideCount || '—'} 页</span>
        <p className="slide-card-summary">{item.summary}</p>
      </div>
    </article>
  )
}
