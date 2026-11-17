import { useEffect, useState } from 'react'
import { createPreviewSession } from '../../api/projects'
import type { PreviewSession } from '../../api/projects'

type PreviewState =
  | { status: 'loading' }
  | { status: 'ready'; session: PreviewSession; versionId: string }
  | { status: 'error'; message: string }

export function ProjectPreview({ id, versionId, video = false }: { id: string; versionId: string; video?: boolean }) {
  const [state, setState] = useState<PreviewState>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    let disposed = false
    setState({ status: 'loading' })
    createPreviewSession(id, controller.signal, versionId)
      .then((session) => {
        if (!disposed) setState({ status: 'ready', session, versionId })
      })
      .catch((error: unknown) => {
        if (!disposed) {
          setState({
            status: 'error',
            message: controller.signal.aborted
              ? '预览准备超时，请重试。'
              : error instanceof Error ? error.message : '暂时无法打开预览。',
          })
        }
      })
    return () => {
      disposed = true
      controller.abort()
    }
  }, [id, versionId, attempt])

  if (state.status === 'loading' || (state.status === 'ready' && state.versionId !== versionId)) {
    return <p className="preview-status" role="status">正在准备版本预览…</p>
  }
  if (state.status === 'error') {
    return (
      <div className="preview-status" role="alert">
        <p>{state.message}</p>
        <button type="button" className="secondary-button" onClick={() => setAttempt((value) => value + 1)}>重新准备预览</button>
      </div>
    )
  }

  const url = `${state.session.baseUrl}${state.session.entryPath}`
  return (
    <div className="preview-shell">
      {video
        ? <video className="video-preview" src={url} controls playsInline preload="metadata" aria-label="所选版本视频预览" />
        : <iframe className="preview-frame" src={url} title="所选版本预览"
          sandbox="allow-scripts allow-pointer-lock" referrerPolicy="no-referrer" />}
      <p className="preview-note">
        预览通过一次性令牌加载所选版本，链接在 {new Date(state.session.expiresAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })} 失效。
        <a href={url} target="_blank" rel="noreferrer noopener">在新标签页打开</a>
      </p>
    </div>
  )
}
