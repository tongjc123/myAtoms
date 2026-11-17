import type { TemplateItem } from '../../api/templates'
import { useHoverPeek } from '../templates/useHoverPeek'
import { WebFrame } from './WebFrame'

export function WebTemplateCard({ item, onPreview, onUse }: {
  item: TemplateItem
  onPreview: () => void
  onUse: () => void
}) {
  const peek = useHoverPeek()

  return (
    <article className="web-card" onMouseEnter={peek.start} onMouseLeave={peek.stop}>
      <div className="web-card-cover">
        <button type="button" className="web-cover-btn" onClick={onPreview}
          onFocus={peek.start} onBlur={peek.stop} aria-label={`预览模板 ${item.name}`}>
          {/* The cover runs the page for real (counters, themes) but the frame ignores pointer events. */}
          <WebFrame src={item.playUrl} title={`${item.name} 预览`} interactive />
          {peek.peeking && (
            <div className="web-peek" aria-hidden="true">
              <WebFrame src={`${item.playUrl}?peek=1`} title="" interactive />
            </div>
          )}
          <span className="web-cover-hint">点击预览 · 可交互</span>
        </button>
        <button type="button" className="web-use" onClick={onUse}>使用模板</button>
      </div>
      <div className="web-card-body">
        <span className="web-card-title">
          <strong>{item.name}</strong>
          <em>{item.category}</em>
        </span>
        <p className="web-card-summary">{item.summary}</p>
        <span className="web-card-meta">{item.author} · v{item.versionNo}</span>
      </div>
    </article>
  )
}
