import { useCallback, useEffect, useState } from 'react'
import { getProject } from '../../api/projects'
import { Icon } from '../../components/Icon'
import { ProjectError } from '../projects/ProjectError'
import { ProjectActionComposer } from '../projects/ProjectActionComposer'
import { ProjectPreview } from '../projects/ProjectPreview'
import { useProjectQuery } from '../projects/useProjectQuery'
import { useRunStream } from './useRunStream'
import { VideoPreview } from './VideoPreview'

const statusLabels: Record<string, string> = {
  QUEUED: '排队中', RUNNING: '生成中', SUCCEEDED: '已完成', FAILED: '生成失败', CANCELLED: '已停止',
}

function DeckSkeleton() {
  return (
    <div className="deck-skeleton" aria-hidden="true">
      <div className="skeleton-rail">
        {[0, 1, 2, 3, 4].map((index) => <span key={index} />)}
      </div>
      <div className="skeleton-stage">
        <div className="skeleton-block wide" />
        <div className="skeleton-block" />
        <div className="skeleton-grid">
          {[0, 1, 2, 3].map((index) => <span key={index} />)}
        </div>
      </div>
    </div>
  )
}

function VideoSkeleton() {
  return (
    <div className="video-skeleton" aria-hidden="true">
      <span className="video-skeleton-play" />
      <div className="video-skeleton-lines">
        <span /><span />
      </div>
    </div>
  )
}

export function ChatPage({ id, enabled, onReconnect }: { id: string; enabled: boolean; onReconnect: () => void }) {
  const load = useCallback((signal: AbortSignal) => getProject(id, signal), [id])
  const project = useProjectQuery(load, enabled, true)
  const { view, stopping, stop, retry } = useRunStream(id)
  const [fullscreen, setFullscreen] = useState(false)
  const refreshProject = project.refresh
  const runId = view.run?.id
  const runStatus = view.run?.status
  useEffect(() => {
    if (runId && runStatus !== 'QUEUED' && runStatus !== 'RUNNING') refreshProject()
  }, [runId, runStatus, refreshProject])

  useEffect(() => {
    if (!fullscreen) return
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') setFullscreen(false)
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [fullscreen])

  const artifact = view.artifact
  const status = view.run?.status ?? null
  const running = status === 'QUEUED' || status === 'RUNNING'
  const projectType = project.state.status === 'ready' ? project.state.data.type : null
  const isVideo = artifact?.kind === 'VIDEO' || projectType === 'VIDEO'
  const isGame = artifact?.kind === 'GAME' || projectType === 'GAME'
  const isWeb = artifact?.kind === 'WEB' || projectType === 'WEB' || isGame
  const versionId = (project.state.status === 'ready' ? project.state.data.currentVersion?.id : null)
    ?? artifact?.versionId
  const mediaPath = artifact?.mediaPath ?? (isVideo && versionId ? 'video.mp4' : null)

  return (
    <div className="chat-layout">
      <section className="chat-thread" aria-label="生成对话">
        <header className="chat-head">
          <a className="chat-back" href="#projects" aria-label="返回项目列表"><Icon name="arrow" /></a>
          <div className="chat-title">
            <strong>{project.state.status === 'ready' ? project.state.data.name : '正在读取项目…'}</strong>
            <small>{status ? statusLabels[status] : view.phase === 'none' ? '尚未生成' : '准备中'}</small>
          </div>
          {status && (
            <span className={`chat-status ${status.toLowerCase()}`}>
              <span className="chat-status-dot" />
              {statusLabels[status]}
            </span>
          )}
        </header>

        <div className="chat-scroll">
          {!enabled && <p className="chat-hint">正在连接匿名创作空间…</p>}
          {project.state.status === 'error' && (
            <ProjectError error={project.state.error} onRetry={() => project.refresh()} onReconnect={onReconnect} />
          )}
          {project.refreshError && project.state.status === 'ready' && (
            <ProjectError error={project.refreshError} onRetry={project.refresh} onReconnect={onReconnect} />
          )}

          {view.phase === 'loading' && <p className="chat-hint" role="status">正在读取生成记录…</p>}

          {view.phase === 'none' && (
            <div className="chat-empty">
              <p>这个项目还没有生成任务。</p>
              <a className="secondary-button" href="#home">回到工作台生成</a>
            </div>
          )}

          {view.phase === 'error' && (
            <div className="chat-hint error" role="alert">
              <p>{view.message}</p>
              <button type="button" className="secondary-button" onClick={retry}>重新读取生成状态</button>
            </div>
          )}

          {(view.phase === 'ready' || view.phase === 'error') && project.state.status === 'ready' && (
            <>
              <div className="chat-message user">
                <p>{view.run?.instruction ?? project.state.data.prompt}</p>
                {view.run?.instruction && (
                  <small>修改请求 · 基础版本 {view.run.baseVersionId}</small>
                )}
                {project.state.data.sourceTemplate && (
                  <span className="chat-message-chip">
                    <span className="composer-chip-thumb" data-theme={view.run?.theme ?? 'swiss'}>幻</span>
                    {project.state.data.sourceTemplate.name}
                  </span>
                )}
              </div>

              <div className="chat-agent">
                <div className="chat-agent-head">
                  <span className="chat-agent-avatar">A</span>
                  <div>
                    <strong>{isVideo ? 'Alex · 视频导演' : isGame ? 'Emma · 游戏工程师' : isWeb ? 'Emma · 网页工程师' : 'Alex · 幻灯片工程师'}</strong>
                    <small>
                      {running
                        ? (isVideo ? '正在等待视频模型结果' : isGame ? '正在编写交互游戏' : isWeb ? '正在编写你的网页' : '正在使用本地引擎排版')
                        : view.notice ?? '已结束'}
                    </small>
                  </div>
                  {running && (
                    <button type="button" className="chat-stop" onClick={() => void stop()} disabled={stopping}>
                      <span className="chat-stop-icon" />
                      {stopping ? '正在停止' : '停止'}
                    </button>
                  )}
                </div>

                {view.steps.length === 0 && view.phase === 'ready' && running && (
                  <p className="chat-hint">正在排队，马上开始…</p>
                )}

                <ol className="run-steps">
                  {view.steps.map((step) => {
                    // A step that was still running when the run failed did not finish; showing it
                    // as "in progress" forever would misrepresent the server log.
                    const state = step.state === 'running' && (status === 'FAILED' || status === 'CANCELLED') ? 'failed' : step.state
                    return (
                      <li key={step.id} className={`run-step ${state}`}>
                        <span className="run-step-dot" aria-hidden="true" />
                        <div>
                          <p className="run-step-title">
                            <strong>{step.role}</strong>
                            <em>{step.agent}</em>
                            {step.state === 'running' && running && <span className="run-step-live">进行中</span>}
                            {state === 'failed' && <span className="run-step-failed">未完成</span>}
                          </p>
                          <p className="run-step-detail">{step.detail}</p>
                        </div>
                      </li>
                    )
                  })}
                </ol>

                {running && view.progress && (
                  <div className="run-progress" role="status">
                    <div className="run-progress-bar">
                      <span style={{ width: `${Math.round((view.progress.done / Math.max(1, view.progress.total)) * 100)}%` }} />
                    </div>
                    <p>{view.progress.label} · {view.progress.done}/{view.progress.total}</p>
                  </div>
                )}

                {running && view.steps.length > 0 && (
                  <p className="chat-thinking"><span className="thinking-dots" aria-hidden="true" />Alex 正在思考</p>
                )}

                {artifact && artifact.kind === 'VIDEO' && (
                  <div className="run-result">
                    <div className="run-file">
                      <span className="run-file-icon video">▶</span>
                      <div className="run-file-text">
                        <strong>{artifact.title} · 视频</strong>
                        <small>
                          {artifact.durationSeconds} 秒 · {artifact.ratio === 'auto' ? '自动画幅' : artifact.ratio} · {artifact.resolution}
                          {artifact.sizeBytes ? ` · ${(artifact.sizeBytes / 1048576).toFixed(1)} MB` : ''}
                          {' · 版本 v'}{artifact.versionNo}
                        </small>
                      </div>
                      <button type="button" className="text-button" onClick={() => setFullscreen(true)}>查看</button>
                    </div>
                    <div className="run-version">
                      <span>版本 {artifact.versionNo}</span>
                      <p>由 {artifact.model ?? '视频模型'} 生成「{artifact.title}」</p>
                    </div>
                  </div>
                )}

                {artifact && (artifact.kind === 'WEB' || artifact.kind === 'GAME') && (
                  <div className="run-result">
                    <div className="run-file">
                      <span className="run-file-icon web">◻</span>
                      <div className="run-file-text">
                        <strong>{artifact.title} · {artifact.kind === 'GAME' ? '游戏' : '网页'}</strong>
                        <small>
                          {artifact.sections ? `${artifact.sections} 个区块 · ` : ''}
                          {artifact.sizeBytes ? `${Math.max(1, Math.round(artifact.sizeBytes / 1024))} KB · ` : ''}
                          版本 v{artifact.versionNo}
                          {artifact.tokens ? ` · 用量 ${artifact.tokens} tokens` : ''}
                        </small>
                      </div>
                      <button type="button" className="text-button" onClick={() => setFullscreen(true)}>查看</button>
                    </div>
                    <div className="run-version">
                      <span>版本 {artifact.versionNo}</span>
                      <p>{artifact.tagline ?? `由 ${artifact.model ?? '文本模型'} 生成「${artifact.title}」`}</p>
                    </div>
                  </div>
                )}

                {artifact && artifact.kind === 'SLIDES' && (
                  <div className="run-result">
                    <div className="run-file">
                      <span className="run-file-icon">幻</span>
                      <div className="run-file-text">
                        <strong>{artifact.title} · 演示文稿</strong>
                        <small>{artifact.slideCount} 页 · 16:9 · 版本 v{artifact.versionNo}</small>
                      </div>
                      <button type="button" className="text-button" onClick={() => setFullscreen(true)}>查看</button>
                    </div>
                    <div className="run-version">
                      <span>版本 {artifact.versionNo}</span>
                      <p>生成 {artifact.slideCount} 页「{artifact.title}」</p>
                    </div>
                  </div>
                )}

                {status === 'CANCELLED' && (
                  <p className="chat-hint warn">已停止生成，未写入新版本。你可以在下方重新提交。</p>
                )}
                {status === 'FAILED' && (
                  <p className="chat-hint error">生成中断：{view.run?.errorMessage ?? '请稍后重试'}</p>
                )}
                {status === 'FAILED' && view.run?.errorCode === 'PROVIDER_MODEL_NOT_ACTIVATED' && (
                  <p className="chat-hint warn">在服务商控制台开通所选模型后即可直接重试，流程不会变化。</p>
                )}
              </div>
            </>
          )}
        </div>

        <div className="chat-composer">
          {project.state.status === 'ready' && (
            <ProjectActionComposer project={project.state.data}
              enabled={enabled && !running && view.phase !== 'loading'} onReconnect={onReconnect}
              onStarted={() => { project.refresh(); retry() }} />
          )}
          {running && <p role="status">当前任务结束后可以继续修改。</p>}
        </div>
      </section>

      <section className="chat-preview" aria-label="作品预览">
        <header className="chat-preview-head">
          <span className="chat-preview-title">{artifact ? artifact.title : '作品预览'}</span>
          {artifact && (
            <span className="chat-preview-actions">
              <button type="button" className="text-button" onClick={() => setFullscreen(true)}>全屏查看</button>
            </span>
          )}
        </header>
        <div className="chat-preview-body">
          {running || view.phase === 'loading' ? (isVideo ? <VideoSkeleton /> : <DeckSkeleton />) : null}
          {!running && versionId && !isVideo && <ProjectPreview id={id} versionId={versionId} />}
          {!running && versionId && isVideo && mediaPath && (
            <VideoPreview id={id} versionId={versionId} mediaPath={mediaPath}
              title={artifact?.title ?? '生成的视频'} />
          )}
          {!running && !versionId && view.phase !== 'loading' && (
            <div className="chat-preview-empty">
              <p>还没有可预览的作品。</p>
              <p>生成完成或停止前，预览区会保持占位状态。</p>
              {view.phase === 'error' && <button type="button" className="secondary-button" onClick={retry}>重新读取</button>}
            </div>
          )}
        </div>
      </section>

      {fullscreen && versionId && (
        <div className="chat-full" role="dialog" aria-modal="true" aria-label="全屏预览">
          <header>
            <strong>{artifact?.title ?? '作品预览'}</strong>
            <button type="button" className="icon-button" aria-label="关闭全屏预览" onClick={() => setFullscreen(false)}>
              <Icon name="close" />
            </button>
          </header>
          <div className="chat-full-body">
            {isVideo && mediaPath
              ? <VideoPreview id={id} versionId={versionId} mediaPath={mediaPath} title={artifact?.title ?? '生成的视频'} autoPlay />
              : <ProjectPreview id={id} versionId={versionId} />}
          </div>
        </div>
      )}
    </div>
  )
}
