import { useRef, useState } from 'react'
import type { ProjectType } from '../../api/capabilities'
import { createProject } from '../../api/projects'
import type { ProjectMode } from '../../api/projects'
import { Icon } from '../../components/Icon'
import { openProject } from '../projects/navigation'
import { ProjectError } from '../projects/ProjectError'
import { useProjectMutation } from '../projects/useProjectMutation'

export function DraftComposer({ type, label, placeholder, enabled, onReconnect }: {
  type: ProjectType
  label: string
  placeholder: string
  enabled: boolean
  onReconnect: () => void
}) {
  const [name, setName] = useState('')
  const [prompt, setPrompt] = useState('')
  const [mode, setMode] = useState<ProjectMode>('BUILD')
  const [attempted, setAttempted] = useState(false)
  const nameRef = useRef<HTMLInputElement>(null)
  const promptRef = useRef<HTMLTextAreaElement>(null)
  const mutation = useProjectMutation()

  const nameMissing = name.trim().length === 0
  const promptMissing = prompt.trim().length === 0
  const valid = !nameMissing && !promptMissing
  const showMissing = attempted && !valid

  // The submit button stays clickable: a disabled button with no visible reason
  // reads as "saving is broken" instead of "a field is still empty".
  const hint = !enabled
    ? '正在连接匿名创作空间，连接成功后即可保存草稿。'
    : showMissing
      ? `还需要填写${nameMissing && promptMissing ? '项目名称和需求描述' : nameMissing ? '项目名称' : '需求描述'}，填写后即可保存。`
      : '仅保存名称、需求、类型与模式，不会启动生成。尚未保存的输入仅保留在当前页面。'

  return (
    <form className="draft-form" noValidate onSubmit={(event) => {
      event.preventDefault()
      if (!enabled || mutation.busy) return
      setAttempted(true)
      if (!valid) {
        if (nameMissing) nameRef.current?.focus()
        else promptRef.current?.focus()
        return
      }
      const body = { name: name.trim(), prompt: prompt.trim(), type, mode }
      void mutation.run(
        JSON.stringify(['POST', '/api/projects', body]),
        (key, signal) => createProject(body, key, signal),
        (project) => openProject(project.id),
      )
    }}>
      <div className="composer">
        <label className="field-label" htmlFor="project-name">项目名称</label>
        <input id="project-name" className="project-name-input" value={name} ref={nameRef}
          maxLength={80} aria-required="true" aria-invalid={showMissing && nameMissing}
          placeholder="给这个想法起个名字" disabled={mutation.busy} autoComplete="off"
          onChange={(event) => setName(event.target.value)} />
        <label className="field-label" htmlFor="prompt">描述你的{label}需求</label>
        <textarea id="prompt" value={prompt} disabled={mutation.busy} ref={promptRef}
          aria-required="true" aria-invalid={showMissing && promptMissing}
          onChange={(event) => setPrompt(event.target.value)}
          maxLength={8000} placeholder={placeholder} aria-describedby="draft-explanation" />
        <div className="composer-toolbar">
          <button className="icon-button" type="button" disabled aria-label="添加附件（第二阶段开放）"
            title="附件将在第二阶段开放"><Icon name="plus" /></button>
          <span className="composer-hint">{prompt.length} / 8000</span>
          <label className="mode-field" htmlFor="project-mode">模式
            <select id="project-mode" value={mode} disabled={mutation.busy} onChange={(event) => {
              if (event.target.value === 'BUILD' || event.target.value === 'PLAN') setMode(event.target.value)
            }}>
              <option value="BUILD">构建</option>
              <option value="PLAN">规划</option>
            </select>
          </label>
          <button className="primary-button save-draft" type="submit"
            disabled={!enabled || mutation.busy}
            aria-describedby="draft-explanation service-message"
            title={enabled ? '保存当前想法为项目草稿' : '匿名创作空间连接中'}>
            {mutation.busy ? '正在保存…' : '保存项目草稿'}
          </button>
        </div>
      </div>
      <p id="draft-explanation" className={`draft-explanation ${showMissing || !enabled ? 'is-hint' : ''}`}
        role={showMissing ? 'alert' : undefined}>{hint}</p>
      {mutation.error && <ProjectError error={mutation.error} onReconnect={onReconnect} />}
    </form>
  )
}
