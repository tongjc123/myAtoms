import { useEffect, useRef, useState } from 'react'
import type { ImportedTemplate } from '../../api/imports'
import { asApiError } from '../../api/http'
import type { ApiError } from '../../api/http'
import { createProject } from '../../api/projects'
import { startSlidesRun } from '../../api/runs'
import type { TemplateItem } from '../../api/templates'
import { Icon } from '../../components/Icon'
import { openChat } from '../projects/navigation'
import { ProjectError } from '../projects/ProjectError'

export type SlideSelection =
  | { kind: 'template'; item: TemplateItem }
  | { kind: 'import'; item: ImportedTemplate }
  | null

/**
 * Mirrors the server's title cleanup so the project list, the chat header and the deck cover all
 * show the same short label instead of a truncated prompt.
 */
export function deriveName(prompt: string) {
  const line = prompt.replace(/\s+/g, ' ').trim()
  const first = line.split(/[。！？；;\n]|\.\s/)[0]?.split(/[，,]/)[0] ?? ''
  const cleaned = first
    .replace(/[「」『』"']/g, '')
    .replace(/^(?:帮我|请帮我|请|我们|我|希望|需要|想要|准备|要|想)/, '')
    .replace(/^(?:为|给|针对|围绕|关于|做|制作|生成|输出|写|策划)/, '')
    .replace(/^(?:一[套份个])(?:关于)?/, '')
    .replace(/(?:做|制作|撰写|写|策划|生成|输出)(?:一[套份个])?(?=提案|方案|演示|汇报|报告|计划|总结|复盘|介绍|分享|发布|推广|宣讲|路演|规划)/, '')
    .replace(/[，。；;：:、\s]+$/, '')
    .trim()
  const value = cleaned.length >= 2 ? cleaned : first.trim()
  if (!value) return ''
  return value.length <= 18 ? value : `${value.slice(0, 17)}…`
}

/** A template selection seeds an editable example request instead of a dangling fragment. */
function seedPrompt(selection: NonNullable<SlideSelection>) {
  if (selection.kind === 'template') {
    return `用「${selection.item.name}」的版式做一套演示：季度结果与关键数据，三条业务线的进展和问题，下一阶段的目标与打法。`
  }
  return `按导入的「${selection.item.name}」大纲重排一套演示，保留原有信息，并在最后补一页结论。`
}

export function SlideComposer({ enabled, selection, onClear, onReconnect }: {
  enabled: boolean
  selection: SlideSelection
  onClear: () => void
  onReconnect: () => void
}) {
  const [prompt, setPrompt] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const seededRef = useRef<string | null>(null)

  useEffect(() => {
    if (!selection) return
    setPrompt((current) => {
      if (current.trim()) return current
      const seeded = seedPrompt(selection)
      seededRef.current = seeded
      return seeded
    })
    textareaRef.current?.focus()
  }, [selection])

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (!enabled || !prompt.trim() || busy) return
    const controller = new AbortController()
    setBusy(true)
    setError(null)
    // An untouched template seed says nothing about the subject, so the template name is the
    // honest label; once the visitor rewrites it, their words win.
    const untouched = seededRef.current !== null && prompt.trim() === seededRef.current.trim()
    const name = untouched && selection ? `${selection.item.name} 演示` : deriveName(prompt)
    try {
      const project = await createProject({
        name: name || '未命名演示',
        prompt: prompt.trim(),
        type: 'SLIDES',
        mode: 'BUILD',
      }, crypto.randomUUID(), controller.signal)
      await startSlidesRun(project.id, {
        templateSlug: selection?.kind === 'template' ? selection.item.slug : null,
        importId: selection?.kind === 'import' ? selection.item.id : null,
      }, controller.signal)
      openChat(project.id)
    } catch (cause: unknown) {
      setError(asApiError(cause))
      setBusy(false)
    }
  }

  return (
    <form className="slide-composer" onSubmit={submit}>
      <div className="composer">
        {selection && (
          <div className="composer-chip">
            <span className="composer-chip-thumb" data-theme={selection.kind === 'template' ? selection.item.theme ?? 'swiss' : 'file'}>
              {selection.kind === 'template' ? '幻' : '文'}
            </span>
            <span className="composer-chip-text">
              <strong>{selection.item.name}</strong>
              <small>
                {selection.kind === 'template'
                  ? `幻灯片模板 · ${selection.item.category}`
                  : `导入的模板 · ${selection.item.note}`}
              </small>
            </span>
            <button type="button" className="composer-chip-remove" aria-label="移除引用的模板" onClick={onClear}>
              <Icon name="close" />
            </button>
          </div>
        )}
        <label className="sr-only" htmlFor="slide-prompt">描述你的幻灯片需求</label>
        <textarea ref={textareaRef} id="slide-prompt" value={prompt} disabled={busy} maxLength={8000}
          onChange={(event) => setPrompt(event.target.value)}
          placeholder="描述你的演示、受众和重点内容。例如：为「创意破界」文化节做一套提案，面向品牌合作方，包含三大板块与时间线。" />
        <div className="composer-toolbar">
          <span className="composer-kind"><Icon name="slides" /> 幻灯片</span>
          <span className="composer-hint">{prompt.length} / 8000</span>
          <button className="primary-button" type="submit" disabled={!enabled || !prompt.trim() || busy}>
            {busy ? '正在创建…' : '开始生成'}
          </button>
        </div>
      </div>
      <p className="slide-composer-hint">
        生成过程会逐页展示智能体的思考步骤，可随时停止；完成后可预览整套演示。
      </p>
      {error && <ProjectError error={error} onReconnect={onReconnect} />}
    </form>
  )
}
