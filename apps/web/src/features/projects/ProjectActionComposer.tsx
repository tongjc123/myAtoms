import { useEffect, useRef, useState } from 'react'
import { ApiError } from '../../api/http'
import { getProject } from '../../api/projects'
import type { Project } from '../../api/projects'
import { startRun } from '../../api/runs'
import type { StartRunInput } from '../../api/runs'
import { fetchVideoOptions } from '../../api/videos'
import type { VideoSpec } from '../../api/videos'
import { VideoSettings } from '../video/VideoSettings'
import { openChat } from './navigation'
import { ProjectError } from './ProjectError'
import { typeLabels } from './projectLabels'
import { useProjectMutation } from './useProjectMutation'
import { useProjectQuery } from './useProjectQuery'

export function ProjectActionComposer({ project, enabled, onReconnect, onStarted }: {
  project: Project
  enabled: boolean
  onReconnect: () => void
  onStarted?: () => void
}) {
  const [latest, setLatest] = useState(project)
  const [instruction, setInstruction] = useState('')
  const [spec, setSpec] = useState<VideoSpec | null>(null)
  const mutation = useProjectMutation()
  const refresh = useProjectMutation()
  const options = useProjectQuery(fetchVideoOptions, enabled && project.type === 'VIDEO')
  // Keep the exact payload as well as the key if the POST outcome is unknown.
  const pending = useRef<{ intent: string; payload: StartRunInput } | null>(null)
  useEffect(() => { setLatest(project) }, [project])
  const editable = latest.type === 'WEB' || latest.type === 'GAME'
  const conflict = mutation.error?.code === 'VERSION_CONFLICT'
  const overlapping = mutation.error?.code === 'RUN_IN_PROGRESS'
  const busy = mutation.busy || refresh.busy
  const videoOptions = options.state.status === 'ready' ? options.state.data : null
  const canGenerate = enabled && !busy && !conflict && !overlapping
    && (latest.type !== 'VIDEO' || !!spec?.model && !!videoOptions)

  function submit(edit: boolean) {
    if (!canGenerate || edit && (!instruction.trim() || !latest.currentVersion)) return
    const intent = JSON.stringify([project.id, edit ? instruction.trim() : null,
      latest.type === 'VIDEO' ? spec : null])
    void mutation.run(intent, async (key, signal) => {
      let payload = pending.current?.intent === intent ? pending.current.payload : null
      if (!payload) {
        // Read before binding the edit. Never silently apply an instruction to a newer version.
        const current = await getProject(project.id, signal)
        if (edit && current.currentVersion?.id !== latest.currentVersion?.id) {
          throw new ApiError('当前作品已更新，请读取最新版本并核对修改说明。', 'VERSION_CONFLICT', false, undefined, 409)
        }
        setLatest(current)
        payload = edit
          ? { instruction: instruction.trim(), baseVersionId: current.currentVersion!.id }
          : latest.type === 'VIDEO' && spec ? { ...spec }
            : latest.type === 'SLIDES' ? { templateSlug: current.sourceTemplate?.slug ?? null, importId: null } : {}
        pending.current = { intent, payload }
      }
      try {
        return await startRun(project.id, payload, signal, key)
      } catch (cause: unknown) {
        // A definite rejection can be corrected; transport/server uncertainty keeps the receipt.
        if (cause instanceof ApiError && cause.status && cause.status >= 400 && cause.status < 500) {
          pending.current = null
        }
        throw cause
      }
    }, () => {
      pending.current = null
      setInstruction('')
      if (onStarted) onStarted()
      else openChat(project.id)
    })
  }

  function refreshBase() {
    void refresh.run(`refresh-${project.id}`, (_key, signal) => getProject(project.id, signal), (current) => {
      setLatest(current)
      pending.current = null
      mutation.clearError()
    })
  }

  return (
    <div className="project-action-composer" data-testid="project-action-composer">
      <p className="field-hint">
        {latest.type === 'SLIDES'
          ? '使用本地排版引擎生成幻灯片，不调用文本模型。'
          : latest.type === 'VIDEO'
            ? '使用已保存的需求和下方规格生成视频。模型状态与失败原因会如实显示，不会自动重试付费任务。'
            : `调用真实文本模型生成${typeLabels[latest.type]}；成功后保存新版本，失败保留当前作品。`}
      </p>
      {editable && latest.currentVersion && (
        <form onSubmit={(event) => { event.preventDefault(); submit(true) }}>
          <label className="field-label" htmlFor={`instruction-${project.id}`}>
            修改说明 · 基于 v{latest.currentVersion.number}
          </label>
          <textarea id={`instruction-${project.id}`} data-testid="generation-instruction"
            value={instruction} onChange={(event) => setInstruction(event.target.value)}
            maxLength={8000} rows={3} disabled={busy} required
            placeholder="描述想修改的内容，例如调整配色、布局或游戏规则。" />
          <button type="submit" className="primary-button" data-testid="submit-edit"
            disabled={!canGenerate || !instruction.trim()}>
            {mutation.busy ? '正在提交…' : '提交修改'}
          </button>
        </form>
      )}
      {latest.type === 'VIDEO' && (
        <>
          {options.state.status === 'loading' && <p role="status">正在读取视频模型与规格…</p>}
          {options.state.status === 'error' && <ProjectError error={options.state.error}
            onRetry={options.refresh} onReconnect={onReconnect} />}
          {videoOptions && <VideoSettings options={videoOptions} spec={spec} onSpecChange={setSpec} disabled={busy} />}
          {videoOptions && <p className="field-hint" role="status">{videoOptions.providerDetail}</p>}
        </>
      )}
      <div className="action-row">
        <button type="button" className="secondary-button" data-testid="start-generation"
          disabled={!canGenerate} onClick={() => submit(false)}>
          {mutation.busy ? '正在提交…' : `${latest.currentVersion ? '重新生成' : '开始生成'}${typeLabels[latest.type]}`}
        </button>
        <a href={`#chat/${project.id}`}>查看生成记录</a>
      </div>
      {editable && latest.currentVersion && <p className="field-hint">重新生成使用原始需求；提交修改会使用当前版本和修改说明。</p>}
      {mutation.error && <ProjectError error={mutation.error} onReconnect={onReconnect}
        onRetry={conflict || overlapping ? refreshBase : undefined} />}
      {refresh.error && <ProjectError error={refresh.error} onReconnect={onReconnect} onRetry={refreshBase} />}
      {mutation.error && !conflict && !overlapping && (
        <p className="field-hint">输入已保留。结果不确定时，请用相同输入重试，系统会核对同一次请求。</p>
      )}
    </div>
  )
}
