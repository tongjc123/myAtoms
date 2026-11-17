import { useEffect, useRef, useState } from 'react'
import type { TemplateItem } from '../../api/templates'
import { cloneTemplate } from '../../api/templates'
import { Icon } from '../../components/Icon'
import { openProject } from '../projects/navigation'
import { useProjectMutation } from '../projects/useProjectMutation'

export function TemplateDialog({ item, canClone, onClose }: {
  item: TemplateItem
  canClone: boolean
  onClose: () => void
}) {
  const [mode, setMode] = useState<'play' | 'clone'>('play')
  const [name, setName] = useState(item.name)
  const mutation = useProjectMutation()
  const closeRef = useRef<HTMLButtonElement>(null)
  const nameRef = useRef<HTMLInputElement>(null)
  const busy = mutation.busy

  useEffect(() => {
    closeRef.current?.focus()
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape' && !mutation.busy) {
        event.preventDefault()
        if (mode === 'clone') setMode('play')
        else onClose()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('keydown', handleKeyDown)
      document.body.style.overflow = previousOverflow
    }
  }, [mode, mutation.busy, onClose])

  useEffect(() => {
    if (mode === 'clone' && !busy) nameRef.current?.focus()
  }, [mode, busy])

  const trimmed = name.trim()
  const canSubmit = canClone && trimmed.length > 0 && trimmed.length <= 80 && !busy

  return (
    <div className="template-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget && !busy) onClose()
    }}>
      <div className="template-modal" role="dialog" aria-modal="true" aria-labelledby="template-dialog-title">
        <div className="template-stage">
          <iframe className="template-frame" src={item.playUrl} title={`${item.name}试玩`}
            sandbox="allow-scripts allow-pointer-lock" referrerPolicy="no-referrer" loading="eager" />

          {mode === 'clone' && (
            <div className="clone-backdrop">
              <form className="clone-card" aria-labelledby="clone-title" onSubmit={(event) => {
                event.preventDefault()
                if (!canSubmit) return
                void mutation.run(
                  JSON.stringify(['CLONE', item.slug, trimmed]),
                  (key, signal) => cloneTemplate(item.slug, trimmed, key, signal),
                  (project) => { onClose(); openProject(project.id) },
                )
              }}>
                <div className="clone-heading">
                  <h3 id="clone-title">从当前版本重新制作</h3>
                  <button type="button" className="icon-button clone-close" aria-label="关闭克隆对话框"
                    disabled={busy} onClick={() => setMode('play')}><Icon name="close" /></button>
                </div>
                <p className="clone-hint">复制 v{item.versionNo} 的完整文件快照到新项目，之后修改不会影响模板本身。</p>
                <label className="clone-field">
                  <span>项目名称</span>
                  <input ref={nameRef} value={name} maxLength={80} required autoComplete="off" disabled={busy}
                    onChange={(event) => setName(event.target.value)} />
                </label>
                <label className="clone-field">
                  <span>目标工作区</span>
                  <select value="current" disabled>
                    <option value="current">我的创作空间 · 匿名访问</option>
                  </select>
                </label>
                {mutation.error && (
                  <p className="clone-error" role="alert">{mutation.error.message}
                    <button type="button" className="text-button" onClick={() => mutation.clearError()}>知道了</button>
                  </p>
                )}
                <div className="clone-actions">
                  <button type="button" className="secondary-button" disabled={busy} onClick={() => setMode('play')}>取消</button>
                  <button type="submit" className="primary-button" disabled={!canSubmit}>
                    {busy ? '正在克隆…' : '克隆'}
                  </button>
                </div>
                {!canClone && <p className="clone-error" role="status">匿名会话尚未连接，暂时不能克隆。</p>}
              </form>
            </div>
          )}

          {busy && (
            <div className="clone-backdrop" role="status" aria-live="polite">
              <div className="clone-card clone-progress">
                <span className="clone-spinner" aria-hidden="true" />
                <h3>正在克隆项目</h3>
                <p>正在请求服务端复制模板文件快照，请稍候。</p>
              </div>
            </div>
          )}
        </div>

        <aside className="template-panel">
          <div className="template-panel-head">
            <div>
              <h2 id="template-dialog-title">{item.name}</h2>
              <p className="template-category">{item.category} · v{item.versionNo}</p>
            </div>
            <button ref={closeRef} type="button" className="icon-button" aria-label="关闭试玩" onClick={onClose}>
              <Icon name="close" />
            </button>
          </div>
          <p className="template-description">{item.description}</p>
          <dl className="template-meta">
            <div><dt>分类</dt><dd>{item.category}</dd></div>
            <div><dt>作者</dt><dd>{item.author}</dd></div>
            <div><dt>来源</dt><dd>{item.sourceUrl ? <a href={item.sourceUrl} target="_blank" rel="noreferrer noopener">{item.sourceUrl}</a> : '平台自研示例'}</dd></div>
            {item.licenseNote && <div><dt>授权</dt><dd>{item.licenseNote}</dd></div>}
          </dl>
          <div className="template-actions">
            <a className="secondary-button" href={item.playUrl} target="_blank" rel="noreferrer noopener">在新标签页中打开</a>
            <button type="button" className="primary-button" onClick={() => setMode('clone')}>
              基于这个游戏构建
            </button>
          </div>
          <p className="template-footnote">试玩区域运行在沙箱环境中，只能加载该模板自身的文件，无法访问你的会话或工作台数据。</p>
        </aside>
      </div>
    </div>
  )
}
