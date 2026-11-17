import { useState } from 'react'
import { asApiError } from '../../api/http'
import type { ApiError } from '../../api/http'
import { createProject } from '../../api/projects'
import { startVideoRun } from '../../api/runs'
import { randomUuid } from '../../api/uuid'
import { VideoSettings } from './VideoSettings'
import type { VideoOptions, VideoSpec } from '../../api/videos'
import { Icon } from '../../components/Icon'
import { openChat } from '../projects/navigation'
import { ProjectError } from '../projects/ProjectError'
import { deriveName } from '../slides/SlideComposer'

export function VideoComposer({ enabled, options, spec, onSpecChange, prompt, onPromptChange,
  onReconnect }: {
  enabled: boolean
  options: VideoOptions | null
  spec: VideoSpec | null
  onSpecChange: (spec: VideoSpec) => void
  prompt: string
  onPromptChange: (value: string) => void
  onReconnect: () => void
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const selected = options?.models.find((model) => model.key === spec?.model) ?? null
  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (!enabled || !prompt.trim() || busy || !spec?.model) return
    const controller = new AbortController()
    setBusy(true)
    setError(null)
    try {
      const project = await createProject({
        name: deriveName(prompt) || '未命名视频',
        prompt: prompt.trim(),
        type: 'VIDEO',
        mode: 'BUILD',
      }, randomUuid(), controller.signal)
      await startVideoRun(project.id, {
        model: spec.model, ratio: spec.ratio, resolution: spec.resolution,
        durationSeconds: spec.durationSeconds,
      }, controller.signal)
      openChat(project.id)
    } catch (cause: unknown) {
      setError(asApiError(cause))
      setBusy(false)
    }
  }

  const canSubmit = enabled && prompt.trim().length > 0 && !busy && !!spec?.model
  // A model that is not activated upstream still lets the form submit, so warn
  // before the run fails instead of after.
  const modelWarning = !enabled || prompt.trim().length === 0
    ? null
    : !spec?.model
      ? '当前没有可用的视频模型，请先在「模型」菜单里选择。'
      : selected && !selected.available
        ? `「${selected.label}」尚未在服务端接入，现在发起会在第一步失败；请换用已接入的模型。`
        : null

  return (
    <form className="video-composer" onSubmit={submit}>
      <div className="composer video-composer-card">
        <label className="sr-only" htmlFor="video-prompt">描述你想生成的视频</label>
        <textarea id="video-prompt" value={prompt} disabled={busy} maxLength={4000}
          rows={3}
          onChange={(event) => onPromptChange(event.target.value)}
          placeholder="描述画面、光线与镜头运动。例如：雨夜霓虹街头，一位撑透明雨伞的行人缓步前行，积水倒映着灯箱，镜头缓慢前推。" />
        <div className="composer-toolbar video-toolbar">
          <span className="composer-kind"><Icon name="video" /> 视频</span>

          <VideoSettings options={options} spec={spec} onSpecChange={onSpecChange} disabled={busy} />
          <span className="composer-spacer" />
          <span className="composer-hint">{prompt.length} / 4000</span>
          <button className="video-send" type="submit" disabled={!canSubmit} aria-label="发起生成任务">↑</button>
        </div>
      </div>
      {modelWarning && (
        <p className="composer-note is-hint" role="status">{modelWarning}</p>
      )}
      {error && <ProjectError error={error} onReconnect={onReconnect} />}
    </form>
  )
}
