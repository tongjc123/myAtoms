import { useEffect, useRef, useState } from 'react'

/**
 * Renders a page at a fixed design viewport (1280×800 by default) and scales it down to the
 * available width. Template cards therefore show the real page instead of a screenshot, and the
 * dialog can flip between a desktop and a phone viewport without a second implementation.
 */
export function WebFrame({ src, title, viewportWidth = 1280, viewportHeight = 800, interactive = false }: {
  src: string
  title: string
  viewportWidth?: number
  viewportHeight?: number
  interactive?: boolean
}) {
  const holder = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(0)

  useEffect(() => {
    const node = holder.current
    if (!node) return
    setWidth(node.getBoundingClientRect().width)
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver((entries) => {
      const next = entries[0]?.contentRect.width ?? 0
      if (next > 0) setWidth(next)
    })
    observer.observe(node)
    return () => observer.disconnect()
  }, [])

  const scale = width > 0 ? width / viewportWidth : 0

  return (
    <div ref={holder} className="web-frame" style={{ aspectRatio: `${viewportWidth} / ${viewportHeight}` }}>
      {scale > 0 && (
        <iframe
          className="web-frame-page"
          src={src}
          title={title}
          loading="lazy"
          tabIndex={interactive ? 0 : -1}
          sandbox={interactive ? 'allow-scripts allow-forms' : ''}
          referrerPolicy="no-referrer"
          style={{ width: viewportWidth, height: viewportHeight, transform: `scale(${scale})` }}
        />
      )}
    </div>
  )
}
