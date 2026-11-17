import { useCallback } from 'react'
import { listProjects } from '../../api/projects'
import { Icon } from '../../components/Icon'
import { ProjectError } from './ProjectError'
import { formatProjectDate, modeLabels, typeLabels } from './projectLabels'
import { useProjectQuery } from './useProjectQuery'

export function ProjectList({ page = 0, recent = false, enabled, onReconnect }: {
  page?: number
  recent?: boolean
  enabled: boolean
  onReconnect: () => void
}) {
  const pageSize = recent ? 4 : 20
  const load = useCallback((signal: AbortSignal) => listProjects(page, pageSize, signal), [page, pageSize])
  const { state, refresh } = useProjectQuery(load, enabled)
  if (!enabled) return <div className="empty-projects"><p>连接项目服务和匿名会话后，将显示已保存的草稿。</p></div>
  if (state.status === 'idle' || state.status === 'loading') {
    return <div className="empty-projects" role="status">正在读取项目…</div>
  }
  if (state.status === 'error') return <ProjectError error={state.error} onRetry={refresh} onReconnect={onReconnect} />
  const { items, total } = state.data
  const lastPage = Math.max(0, Math.ceil(total / pageSize) - 1)

  return (
    <div className="project-list">
      <div className="list-toolbar">
        <span>共 {total} 个草稿</span>
        <button type="button" className="text-button" onClick={refresh}>刷新列表</button>
      </div>
      {items.length === 0 ? (
        <div className="empty-projects">
          <span className="empty-icon"><Icon name="folder" /></span>
          <h3>{page > 0 ? '这一页已没有项目' : '第一个作品，等你开始'}</h3>
          <p>{page > 0 ? '项目可能已在其他页面删除，请返回已有项目的页面。' : '保存一个项目草稿，把想法留在当前浏览器的创作空间。'}</p>
          {page > 0
            ? <a className="secondary-button" href={`#projects?page=${lastPage}`}>返回有项目的页面</a>
            : <a className="secondary-button" href="#home">新建草稿</a>}
        </div>
      ) : (
        <ul className="project-grid">
          {items.map((project) => (
            <li key={project.id}>
              <a className="project-card" href={`#projects/${project.id}`}>
                <div className="card-heading"><Icon name="folder" />
                  <span className="draft-badge">{project.currentVersion ? `可预览 · v${project.currentVersion.number}` : '草稿 · DRAFT'}</span>
                </div>
                <h3>{project.name}</h3>
                <p>{project.prompt}</p>
                <div className="project-card-meta"><span>{typeLabels[project.type]} · {modeLabels[project.mode]}</span>
                  <time dateTime={project.updatedAt}>{formatProjectDate(project.updatedAt)}</time></div>
              </a>
            </li>
          ))}
        </ul>
      )}
      {!recent && total > 0 && (
        <nav className="pagination" aria-label="项目分页">
          <button type="button" className="secondary-button" disabled={page === 0}
            onClick={() => { window.location.hash = `projects?page=${page - 1}` }}>上一页</button>
          <span aria-live="polite">第 {page + 1} 页 / 共 {lastPage + 1} 页</span>
          <button type="button" className="secondary-button" disabled={page >= lastPage}
            onClick={() => { window.location.hash = `projects?page=${page + 1}` }}>下一页</button>
        </nav>
      )}
      {recent && total > pageSize && <a className="all-projects" href="#projects">查看全部 {total} 个项目 →</a>}
    </div>
  )
}
