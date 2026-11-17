import type { ApiError } from '../../api/http'

export function ProjectError({ error, onRetry, onReconnect }: {
  error: ApiError
  onRetry?: () => void
  onReconnect: () => void
}) {
  const sessionError = error.status === 401 || error.code === 'CSRF_REJECTED'
  const conflict = error.code === 'VERSION_CONFLICT'
  return (
    <div className="project-error" role="alert">
      <p>{error.message}</p>
      {sessionError && <p>请重新连接匿名会话，再重试操作。已输入的内容会保留；清除 Cookie 或会话过期后，原项目可能无法找回。</p>}
      {conflict && <p>项目已在其他页面修改。请先读取最新项目，再确认并重新提交；你的输入会保留。</p>}
      {error.code === 'IDEMPOTENCY_CONFLICT' && <p>请求标识发生冲突，请核对最新项目后重新提交。</p>}
      {error.code === 'PROJECT_NOT_FOUND' && <p>项目可能已删除，或不属于当前浏览器的匿名会话。</p>}
      {error.requestId && <small>请求编号：{error.requestId}</small>}
      <div className="action-row">
        {sessionError
          ? <button type="button" className="secondary-button" onClick={onReconnect}>重新连接会话</button>
          : onRetry && <button type="button" className="secondary-button" onClick={onRetry}>{conflict ? '读取最新项目' : '重新读取'}</button>}
        {error.code === 'PROJECT_NOT_FOUND' && <a href="#projects">返回项目列表</a>}
      </div>
    </div>
  )
}
