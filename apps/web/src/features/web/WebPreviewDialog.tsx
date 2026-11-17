import { useEffect, useRef, useState } from 'react'
import type { TemplateItem } from '../../api/templates'
import { cloneTemplate } from '../../api/templates'
import { Icon } from '../../components/Icon'
import { openProject } from '../projects/navigation'
import { useProjectMutation } from '../projects/useProjectMutation'
import { WebFrame } from './WebFrame'

type Device = 'desktop' | 'phone'

export function WebPreviewDialog({ item, canClone, focusName = false, onClose }: {
  item: TemplateItem
  canClone: boolean
  focusName?: boolean
  onClose: () => void
}) {
  const [device, setDevice] = useState<Device>('desktop')
  const [name, setName] = useState(item.name)
  const closeRef = useRef<HTMLButtonElement>(null)
  const nameRef = useRef<HTMLInputElement>(null)
  const focused = useRef(false)
  const mutation = useProjectMutation()
  const busy = mutation.busy

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    if (!focused.current) {
      focused.current = true
      if (focusName) nameRef.current?.select()
      else closeRef.current?.focus()
    }
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape' && !mutation.busy) {
        event.preventDefault()
        onClose()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = previousOverflow
      previous?.focus()
    }
  }, [focusName, mutation.busy, onClose])

  const trimmed = name.trim()
  const canSubmit = canClone && trimmed.length > 0 && trimmed.length <= 80 && !busy

  return (
    <div className="web-preview-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget && !busy) onClose()
    }}>
      <div className="web-preview" role="dialog" aria-modal="true" aria-labelledby="web-preview-title">
        <header className="web-preview-head">
          <div>
            <h2 id="web-preview-title">{item.name}</h2>
            <p>{item.category} · v{item.versionNo} · 单文件 HTML</p>
          </div>
          <div className="web-device" role="group" aria-label="预览尺寸">
            <button type="button" aria-pressed={device === 'desktop'} onClick={() => setDevice('desktop')}>桌面</button>
            <button type="button" aria-pressed={device === 'phone'} onClick={() => setDevice('phone')}>手机</button>
          </div>
          <button ref={closeRef} type="button" className="icon-button" aria-label="关闭预览" onClick={onClose}>
            <Icon name="close" />
          </button>
        </header>

        <div className={`web-preview-stage ${device}`}>
          <div className="web-preview-device">
            <WebFrame key={device} src={item.playUrl} title={`${item.name} 模板预览`}
              viewportWidth={device === 'phone' ? 390 : 1280}
              viewportHeight={device === 'phone' ? 780 : 800}
              interactive />
          </div>
        </div>

        <footer className="web-preview-foot">
          <p className="web-preview-desc">{item.description}</p>
          <form className="web-clone" onSubmit={(event) => {
            event.preventDefault()
            if (!canSubmit) return
            void mutation.run(
              JSON.stringify(['CLONE', item.slug, trimmed]),
              (key, signal) => cloneTemplate(item.slug, trimmed, key, signal),
              (project) => { onClose(); openProject(project.id) },
            )
          }}>
            <label className="web-clone-field">
              <span>项目名称</span>
              <input ref={nameRef} value={name} maxLength={80} required autoComplete="off" disabled={busy}
                onChange={(event) => setName(event.target.value)} />
            </label>
            <button type="submit" className="primary-button" disabled={!canSubmit}>
              {busy ? '正在复制…' : '使用此模板'}
            </button>
            <a className="text-button" href={item.playUrl} target="_blank" rel="noreferrer noopener">在新标签页打开</a>
          </form>
          {!canClone && <p className="web-preview-hint">匿名会话尚未连接，暂时不能复制模板。</p>}
          {mutation.error && (
            <p className="web-preview-hint error" role="alert">{mutation.error.message}
              <button type="button" className="text-button" onClick={() => mutation.clearError()}>知道了</button>
            </p>
          )}
          <p className="web-preview-hint">使用模板会把当前版本的完整文件复制成一个独立项目，之后修改不会影响模板本身。</p>
        </footer>
      </div>
    </div>
  )
}
