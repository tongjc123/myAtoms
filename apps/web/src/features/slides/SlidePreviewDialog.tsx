import { useEffect, useRef } from 'react'
import type { TemplateItem } from '../../api/templates'

export function SlidePreviewDialog({ item, onClose, onUse }: {
  item: TemplateItem
  onClose: () => void
  onUse: () => void
}) {
  const closeRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    closeRef.current?.focus()
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault()
        onClose()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      previous?.focus()
    }
  }, [onClose])

  return (
    <div className="slide-preview-backdrop" onClick={onClose}>
      <div className="slide-preview" role="dialog" aria-modal="true" aria-label={`${item.name} 模板预览`}
        onClick={(event) => event.stopPropagation()}>
        <header className="slide-preview-head">
          <div>
            <h2>{item.name}</h2>
            <p>{item.category} · {item.slideCount || '—'} 页 · v{item.versionNo}</p>
          </div>
          <button ref={closeRef} type="button" className="icon-button" aria-label="关闭预览" onClick={onClose}>×</button>
        </header>
        <div className="slide-preview-stage">
          <iframe title={`${item.name} 预览`} sandbox="allow-scripts" referrerPolicy="no-referrer"
            src={`${item.playUrl}?slide=1`} />
        </div>
        <footer className="slide-preview-foot">
          <span>左右方向键翻页 · 内容与克隆后的副本一致</span>
          <a className="text-button" href={item.playUrl} target="_blank" rel="noreferrer noopener">在新标签页打开</a>
          <button type="button" className="primary-button" onClick={onUse}>使用这个模板</button>
        </footer>
      </div>
    </div>
  )
}
