import { useCallback, useEffect, useRef, useState } from 'react'
import { deleteProject, getProject, renameProject } from '../../api/projects'
import { ProjectActionComposer } from './ProjectActionComposer'
import { ProjectError } from './ProjectError'
import { ProjectPreview } from './ProjectPreview'
import { ProjectVersionHistory } from './ProjectVersionHistory'
import { formatProjectDate, modeLabels, typeLabels } from './projectLabels'
import { useProjectMutation } from './useProjectMutation'
import { useProjectQuery } from './useProjectQuery'

export function ProjectDetail({ id, enabled, onReconnect }: {
  id: string
  enabled: boolean
  onReconnect: () => void
}) {
  const load = useCallback((signal: AbortSignal) => getProject(id, signal), [id])
  const { state, refresh, refreshError } = useProjectQuery(load, enabled, true)
  const mutation = useProjectMutation()
  const [name, setName] = useState<string | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [notice, setNotice] = useState('')
  const cancelDeleteRef = useRef<HTMLButtonElement>(null)
  const deleteButtonRef = useRef<HTMLButtonElement>(null)
  const wasConfirmingDelete = useRef(false)
  useEffect(() => {
    if (confirmDelete) cancelDeleteRef.current?.focus()
    else if (wasConfirmingDelete.current) deleteButtonRef.current?.focus()
    wasConfirmingDelete.current = confirmDelete
  }, [confirmDelete])

  function refreshProject() {
    setConfirmDelete(false)
    setNotice('')
    mutation.clearError()
    refresh()
  }

  if (!enabled) return <div className="empty-projects"><p>正在等待项目服务和匿名会话连接。</p><a href="#projects">返回项目列表</a></div>
  if (state.status === 'idle' || state.status === 'loading') return <p role="status">正在读取项目草稿…</p>
  if (state.status === 'error') return <ProjectError error={state.error} onRetry={refreshProject} onReconnect={onReconnect} />
  const project = state.data
  const inputName = name ?? project.name
  // A conflict must be refreshed before another mutation can use the revision.
  const blockedByConflict = mutation.error?.code === 'VERSION_CONFLICT'

  return (
    <article className="project-detail">
      {refreshError && <ProjectError error={refreshError} onRetry={refresh} onReconnect={onReconnect} />}
      <div className="section-heading">
        <a className="back-link" href="#projects">← 返回项目列表</a>
        <button type="button" className="secondary-button" disabled={mutation.busy} onClick={() => {
          refreshProject()
          setNotice('已请求最新项目。请核对内容，再提交保留的输入。')
        }}>刷新项目</button>
      </div>
      <span className="draft-badge">{project.currentVersion ? `可预览 · v${project.currentVersion.number}` : '已保存草稿 · DRAFT'}</span>
      <h1>{project.name}</h1>
      <p className="hero-description">
        {project.currentVersion
          ? '这是当前版本的文件快照，模板或模型的后续更新不会改动这里的内容。'
          : project.type === 'WEB'
            ? '需求已保存，可以直接生成网页；生成过程会在新页面实时展示。'
            : '需求已保存，尚未生成作品。'}
      </p>
      <dl className="project-facts">
        <div><dt>类型</dt><dd>{typeLabels[project.type]}</dd></div>
        <div><dt>模式</dt><dd>{modeLabels[project.mode]}</dd></div>
        {project.sourceTemplate && <div><dt>来源模板</dt><dd>{project.sourceTemplate.name}</dd></div>}
        {project.currentVersion && (
          <div><dt>当前版本</dt><dd>v{project.currentVersion.number} · {project.currentVersion.summary}</dd></div>
        )}
        <div><dt>创建时间</dt><dd><time dateTime={project.createdAt}>{formatProjectDate(project.createdAt)}</time></dd></div>
        <div><dt>更新时间</dt><dd><time dateTime={project.updatedAt}>{formatProjectDate(project.updatedAt)}</time></dd></div>
      </dl>
      {project.currentVersion && (
        <section className="detail-panel preview-panel" aria-labelledby="preview-title">
          <div className="section-heading">
            <h2 id="preview-title">作品预览</h2>
            <span className="version-badge">v{project.currentVersion.number} · 文件快照</span>
          </div>
          <ProjectPreview key={project.currentVersion.id} id={project.id} versionId={project.currentVersion.id} video={project.type === 'VIDEO'} />
        </section>
      )}
      <ProjectVersionHistory key={project.id} project={project} onRefresh={refresh} onReconnect={onReconnect} />
      <section className="detail-panel" aria-labelledby="requirement-title">
        <h2 id="requirement-title">已保存的需求</h2>
        <p className="saved-prompt">{project.prompt}</p>
        <ProjectActionComposer project={project} enabled={enabled && !mutation.busy && !confirmDelete}
          onReconnect={onReconnect} />
      </section>
      <section className="detail-panel" aria-labelledby="rename-title">
        <h2 id="rename-title">重命名项目</h2>
        <form className="rename-form" onSubmit={(event) => {
          event.preventDefault()
          const nextName = inputName.trim()
          if (!nextName || mutation.busy || blockedByConflict || nextName === project.name) return
          setNotice('')
          void mutation.run(
            JSON.stringify(['PATCH', id, nextName, project.revision]),
            (key, signal) => renameProject(id, nextName, project.revision, key, signal),
            () => {
              setName(null)
              setNotice('项目名称已保存。')
              refresh()
            },
          )
        }}>
          <label className="sr-only" htmlFor="rename-project">新的项目名称</label>
          <input id="rename-project" value={inputName} required maxLength={80} autoComplete="off"
            disabled={mutation.busy || confirmDelete} onChange={(event) => setName(event.target.value)} />
          <button type="submit" className="primary-button"
            disabled={mutation.busy || confirmDelete || blockedByConflict || !inputName.trim() || inputName.trim() === project.name}>
            {mutation.busy && !confirmDelete ? '正在保存…' : '保存名称'}
          </button>
        </form>
        <p className="field-hint">只有点击“保存名称”后才会更新。刷新项目会保留尚未提交的名称。</p>
      </section>
      {notice && <p role="status" className="success-notice">{notice}</p>}
      {mutation.error && <ProjectError error={mutation.error} onReconnect={onReconnect} onRetry={() => {
        refreshProject()
        setNotice('已请求最新项目。请核对内容，再提交保留的输入。')
      }} />}
      <section className="detail-panel danger-panel" aria-labelledby="delete-title">
        <h2 id="delete-title">删除项目</h2>
        <p className="field-hint">删除后，此草稿会从当前空间移除，并立即无法打开。</p>
        {confirmDelete ? (
          <div className="delete-confirmation" role="group" aria-labelledby="delete-confirmation-title"
            onKeyDown={(event) => {
              if (event.key === 'Escape' && !mutation.busy) {
                setConfirmDelete(false)
              }
            }}>
            <p id="delete-confirmation-title">确定删除“{project.name}”？此操作无法在界面中撤销。</p>
            <div className="action-row">
              <button ref={cancelDeleteRef} type="button" className="secondary-button" disabled={mutation.busy}
                onClick={() => setConfirmDelete(false)}>取消</button>
              <button type="button" className="danger-button" disabled={mutation.busy || blockedByConflict} onClick={() => {
                setNotice('')
                void mutation.run(
                  JSON.stringify(['DELETE', id, project.revision]),
                  (key, signal) => deleteProject(id, project.revision, key, signal),
                  () => { window.location.hash = 'projects' },
                )
              }}>{mutation.busy ? '正在删除…' : '确认删除草稿'}</button>
            </div>
          </div>
        ) : (
          <button ref={deleteButtonRef} type="button" className="danger-button" disabled={mutation.busy || blockedByConflict}
            onClick={() => { setConfirmDelete(true); setNotice('') }}>删除项目…</button>
        )}
      </section>
    </article>
  )
}
