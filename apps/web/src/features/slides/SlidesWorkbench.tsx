import { useRef, useState } from 'react'
import type { ImportedTemplate } from '../../api/imports'
import type { TemplateItem } from '../../api/templates'
import { SlideComposer } from './SlideComposer'
import type { SlideSelection } from './SlideComposer'
import { SlideGallery } from './SlideGallery'
import { useImports } from './useImports'

export function SlidesWorkbench({ enabled, onReconnect }: { enabled: boolean; onReconnect: () => void }) {
  const imports = useImports(enabled)
  const [selection, setSelection] = useState<SlideSelection>(null)
  const composerRef = useRef<HTMLDivElement>(null)

  function apply(next: SlideSelection) {
    setSelection(next)
    composerRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }

  function useTemplate(item: TemplateItem) { apply({ kind: 'template', item }) }
  function useImport(item: ImportedTemplate) { apply({ kind: 'import', item }) }

  return (
    <>
      <section className="hero slide-hero" aria-labelledby="slide-hero-title">
        <span className="stage-badge">幻灯片 · 第三阶段</span>
        <h1 id="slide-hero-title">你想创建什么样的幻灯片？</h1>
        <p className="hero-description">选一套模板，或直接描述你的演示主题。生成时可以看见每一步。</p>
        <div ref={composerRef}>
          <SlideComposer enabled={enabled} selection={selection} onClear={() => setSelection(null)}
            onReconnect={onReconnect} />
        </div>
      </section>

      <SlideGallery enabled={enabled}
        selectedKey={selection ? `${selection.kind}:${selection.kind === 'template' ? selection.item.slug : selection.item.id}` : null}
        imports={{ ...imports, error: imports.error ? imports.error.message : null }}
        onUseTemplate={useTemplate} onUseImport={useImport} />
    </>
  )
}
