import { useCallback, useEffect, useRef, useState } from 'react'
import { asApiError } from '../../api/http'
import type { ApiError } from '../../api/http'
import type { Project, VersionKind } from '../../api/projects'
import { downloadVersionSource, listVersions, restoreVersion } from '../../api/versions'
import type { HistoryVersion } from '../../api/versions'
import { ProjectError } from './ProjectError'
import { ProjectPreview } from './ProjectPreview'
import { formatProjectDate } from './projectLabels'
import { useProjectMutation } from './useProjectMutation'
import { useProjectQuery } from './useProjectQuery'

const kindLabels: Record<VersionKind, string> = {
  TEMPLATE: '模板', GENERATED: '生成', EDIT: '修改', RESTORE: '恢复',
}
type RestoreIntent = { version: HistoryVersion; base: string }

export function ProjectVersionHistory({ project, onRefresh, onReconnect }: {
  project: Project
  onRefresh: () => void
  onReconnect: () => void
}) {
  const [page, setPage] = useState(0)
  const load = useCallback((signal: AbortSignal) => listVersions(project.id, page, 10, signal),
    [project.id, project.revision, project.currentVersion?.id, page])
  const { state, refresh } = useProjectQuery(load, true)
  const [selected, setSelected] = useState<HistoryVersion | null>(null)
  const [intent, setIntent] = useState<RestoreIntent | null>(null)
  const [notice, setNotice] = useState('')
  const mutation = useProjectMutation()
  const dialogRef = useRef<HTMLDialogElement>(null)
  const cancelRef = useRef<HTMLButtonElement>(null)
  const triggerRef = useRef<HTMLButtonElement | null>(null)
  const exportController = useRef<AbortController | null>(null)
  const [exporting, setExporting] = useState<string | null>(null)
  const [exportError, setExportError] = useState<ApiError>()

  useEffect(() => () => exportController.current?.abort(), [])
  useEffect(() => {
    if (intent) {
      dialogRef.current?.showModal()
      cancelRef.current?.focus()
    } else {
      dialogRef.current?.close()
      triggerRef.current?.focus()
    }
  }, [intent])

  const needsRefresh = mutation.error?.status === 409
  const currentId = state.status === 'ready' ? state.data.currentVersionId : null

  function refreshConflict() {
    setIntent(null)
    mutation.clearError()
    refresh()
    onRefresh()
    setNotice('已请求最新版本；保留所选预览。请核对当前版本后重新确认恢复。')
  }

  async function exportSource(version: HistoryVersion) {
    if (exportController.current) return
    const controller = new AbortController()
    exportController.current = controller
    setExporting(version.id)
    setExportError(undefined)
    try {
      await downloadVersionSource(project.id, version.id, controller.signal)
      if (!controller.signal.aborted) setNotice(`v${version.number} 源码 ZIP 已交给浏览器下载。`)
    } catch (cause: unknown) {
      if (!controller.signal.aborted) setExportError(asApiError(cause))
    } finally {
      if (exportController.current === controller) exportController.current = null
      if (!controller.signal.aborted) setExporting(null)
    }
  }

  return (
    <section className="detail-panel history-panel" data-testid="history-panel" aria-labelledby="history-title">
      <div className="section-heading">
        <h2 id="history-title">版本历史</h2>
        <button type="button" className="secondary-button" disabled={mutation.busy || !!intent}
          onClick={() => { refresh(); onRefresh() }}>刷新历史</button>
      </div>
      <p className="field-hint">预览和下载不会改变当前版本。恢复会创建新版本，保留全部历史；不会回滚业务数据、修改公开版或调用模型。</p>
      {(state.status === 'idle' || state.status === 'loading') && <p role="status">正在读取版本历史…</p>}
      {state.status === 'error' && <ProjectError error={state.error} onRetry={refresh} onReconnect={onReconnect} />}
      {state.status === 'ready' && <>
        {state.data.items.length === 0
          ? <p className="empty-projects">暂无版本记录。生成作品或使用模板后，版本将保存在这里。</p>
          : <ol className="history-list" aria-label="历史版本">
            {state.data.items.map(version => <li key={version.id} className="history-row"
              data-testid="history-row" data-version-id={version.id} data-selected={selected?.id === version.id}>
              <div className="history-meta">
                <strong>v{version.number} · {kindLabels[version.kind]}</strong>
                {version.id === currentId && <span className="draft-badge" data-testid="history-current">当前版本</span>}
                <p>{version.summary}</p>
                <time dateTime={version.createdAt}>{formatProjectDate(version.createdAt)}</time>
                {version.restoredFromVersionId && <small>{(() => {
                  const source = state.data.items.find(item => item.id === version.restoredFromVersionId)
                  return source ? `恢复来源：v${source.number}` : '从历史版本恢复'
                })()}</small>}
              </div>
              <div className="action-row">
                <button type="button" className="secondary-button" data-testid="history-preview"
                  aria-pressed={selected?.id === version.id} onClick={() => setSelected(version)}>预览 v{version.number}</button>
                <button type="button" className="secondary-button" data-testid="history-restore"
                  disabled={!currentId || version.id === currentId || mutation.busy || !!intent}
                  onClick={event => {
                    if (!currentId) return
                    triggerRef.current = event.currentTarget
                    mutation.clearError()
                    setNotice('')
                    setIntent({ version, base: currentId })
                  }}>恢复此版本…</button>
                {(project.type === 'WEB' || project.type === 'GAME') && <button type="button" className="secondary-button"
                  data-testid="history-export" disabled={!!exporting} onClick={() => void exportSource(version)}>
                  {exporting === version.id ? '正在下载…' : '下载源码 ZIP'}
                </button>}
              </div>
            </li>)}
          </ol>}
        <nav className="pagination" aria-label="版本历史分页">
          <button type="button" className="secondary-button" data-testid="history-previous"
            disabled={page === 0 || mutation.busy} onClick={() => setPage(value => value - 1)}>上一页</button>
          <span>第 {page + 1} / {Math.max(1, Math.ceil(state.data.total / 10))} 页 · 共 {state.data.total} 个版本</span>
          <button type="button" className="secondary-button" data-testid="history-next"
            disabled={(page + 1) * 10 >= state.data.total || mutation.busy} onClick={() => setPage(value => value + 1)}>下一页</button>
        </nav>
      </>}
      {notice && <p role="status" className="success-notice">{notice}</p>}
      {exportError && <ProjectError error={exportError} onReconnect={onReconnect} />}
      {selected && <section className="history-preview" aria-labelledby="history-preview-title" data-testid="history-selected"
        data-version-id={selected.id}>
        <div className="section-heading">
          <h3 id="history-preview-title">所选版本预览 · v{selected.number}</h3>
          <button type="button" className="secondary-button" onClick={() => setSelected(null)}>关闭预览</button>
        </div>
        <ProjectPreview key={selected.id} id={project.id} versionId={selected.id} video={project.type === 'VIDEO'} />
      </section>}
      <dialog ref={dialogRef} className="restore-dialog" aria-labelledby="restore-title" aria-describedby="restore-description"
        onCancel={event => {
          event.preventDefault()
          if (!mutation.busy) setIntent(null)
        }}>
        {intent && <>
          <h2 id="restore-title">确认恢复 v{intent.version.number}？</h2>
          <p id="restore-description">将以此版本的文件创建一个新的当前版本。已有版本全部保留，业务数据和公开版不变。</p>
          <p className="field-hint">目标：v{intent.version.number} · {intent.version.summary}</p>
          {mutation.error && <>
            <ProjectError error={mutation.error} onReconnect={onReconnect} onRetry={needsRefresh ? refreshConflict : undefined} />
            {!needsRefresh && <p className="field-hint">结果可能已经生效。点击“重试同一次恢复”会核对原请求，不会自动再次恢复。</p>}
          </>}
          <div className="action-row">
            <button ref={cancelRef} type="button" className="secondary-button" disabled={mutation.busy}
              onClick={() => setIntent(null)}>取消</button>
            <button type="button" className="primary-button" data-testid="history-confirm-restore"
              disabled={mutation.busy || needsRefresh} onClick={() => {
                void mutation.run(
                  JSON.stringify(['RESTORE', project.id, intent.version.id, intent.base]),
                  (key, signal) => restoreVersion(project.id, intent.version.id, intent.base, key, signal),
                  result => {
                    setIntent(null)
                    setNotice(`已恢复为新版本 v${result.currentVersion?.number ?? ''}，原有历史已保留。`)
                    setPage(0)
                    refresh()
                    onRefresh()
                  },
                )
              }}>{mutation.busy ? '正在恢复…' : mutation.error ? '重试同一次恢复' : '确认恢复'}</button>
            {needsRefresh && <button type="button" className="secondary-button" data-testid="history-refresh-conflict"
              onClick={refreshConflict}>读取最新版本并重新确认</button>}
          </div>
        </>}
      </dialog>
    </section>
  )
}
