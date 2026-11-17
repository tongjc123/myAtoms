import { useEffect, useRef, useState } from 'react'
import { specLabel } from '../../api/videos'
import type { VideoOptions, VideoSpec } from '../../api/videos'

function useDismiss<T extends HTMLElement>(open: boolean, onClose: () => void) {
  const ref = useRef<T>(null)
  useEffect(() => {
    if (!open) return
    function onPointer(event: MouseEvent) {
      if (ref.current && !ref.current.contains(event.target as Node)) onClose()
    }
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('mousedown', onPointer)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onPointer)
      document.removeEventListener('keydown', onKey)
    }
  }, [open, onClose])
  return ref
}

export function VideoSettings({ options, spec, onSpecChange, disabled = false }: {
  options: VideoOptions | null
  spec: VideoSpec | null
  onSpecChange: (spec: VideoSpec) => void
  disabled?: boolean
}) {
  const [modelOpen, setModelOpen] = useState(false)
  const [specOpen, setSpecOpen] = useState(false)
  const modelRef = useDismiss<HTMLDivElement>(modelOpen, () => setModelOpen(false))
  const specRef = useDismiss<HTMLDivElement>(specOpen, () => setSpecOpen(false))

  const selected = options?.models.find((model) => model.key === spec?.model) ?? null

  useEffect(() => {
    if (options && !spec) {
      onSpecChange({
        model: options.defaultModel ?? options.models[0]?.key ?? '',
        ratio: options.defaultRatio,
        resolution: options.defaultResolution,
        durationSeconds: options.defaultDurationSeconds,
      })
    }
  }, [options, spec, onSpecChange])

  return (
    <fieldset className="video-settings" disabled={disabled} aria-label="视频模型与规格">
          <div className="model-select" ref={modelRef}>
            <button type="button" className="toolbar-chip" aria-haspopup="listbox" aria-expanded={modelOpen}
              onClick={() => { setModelOpen(!modelOpen); setSpecOpen(false) }}>
              <span className="model-cube" aria-hidden="true" />
              {selected ? selected.label : '选择模型'}
              <span className="toolbar-caret" aria-hidden="true" />
            </button>
            {modelOpen && options && (
              <div className="menu model-menu" role="listbox" aria-label="选择视频模型">
                {options.models.map((model) => (
                  <button key={model.key} type="button" role="option"
                    aria-selected={spec?.model === model.key}
                    className={`model-option ${spec?.model === model.key ? 'selected' : ''}`}
                    disabled={!model.available}
                    onClick={() => { onSpecChange({ ...spec!, model: model.key }); setModelOpen(false) }}>
                    <span className="model-cube" aria-hidden="true" />
                    <span className="model-option-text">
                      <strong>{model.label}{model.badge && <em>{model.badge}</em>}</strong>
                      <small>{model.available ? model.description : '服务端未配置模型编号，暂不可用。'}</small>
                    </span>
                    {spec?.model === model.key && <span className="model-check" aria-hidden="true">✓</span>}
                    {!model.available && <span className="model-lock">未接入</span>}
                  </button>
                ))}
              </div>
            )}
          </div>

          <div className="spec-select" ref={specRef}>
            <button type="button" className="toolbar-chip" aria-haspopup="dialog" aria-expanded={specOpen}
              onClick={() => { setSpecOpen(!specOpen); setModelOpen(false) }}>
              <span className="spec-glyph" aria-hidden="true" />
              {spec ? specLabel(spec) : '规格'}
              <span className="toolbar-caret" aria-hidden="true" />
            </button>
            {specOpen && spec && options && (
              <div className="menu spec-menu" role="dialog" aria-label="视频规格">
                <div className="spec-section">
                  <div className="spec-row">
                    <span className="spec-title">比例</span>
                    <label className="spec-auto">
                      智能
                      <input type="checkbox" checked={spec.ratio === null}
                        onChange={(event) => onSpecChange({ ...spec, ratio: event.target.checked ? null : '16:9' })} />
                      <span className="spec-switch" aria-hidden="true" />
                    </label>
                  </div>
                  <div className="ratio-grid" role="group" aria-label="画幅比例">
                    {options.ratios.map((ratio) => (
                      <button key={ratio} type="button"
                        className={`ratio-option ${spec.ratio === ratio ? 'active' : ''}`}
                        aria-pressed={spec.ratio === ratio}
                        onClick={() => onSpecChange({ ...spec, ratio })}>
                        <span className="ratio-frame" data-ratio={ratio} aria-hidden="true" />
                        {ratio}
                      </button>
                    ))}
                  </div>
                </div>
                <div className="spec-section">
                  <span className="spec-title">分辨率</span>
                  <div className="segmented" role="group" aria-label="分辨率">
                    {options.resolutions.map((resolution) => (
                      <button key={resolution} type="button"
                        className={spec.resolution === resolution ? 'active' : ''}
                        aria-pressed={spec.resolution === resolution}
                        onClick={() => onSpecChange({ ...spec, resolution })}>
                        {resolution}
                      </button>
                    ))}
                  </div>
                </div>
                <div className="spec-section">
                  <div className="spec-row">
                    <span className="spec-title">时长</span>
                    <span className="spec-seconds">{spec.durationSeconds}<small> 秒</small></span>
                  </div>
                  <input type="range" min={options.minDurationSeconds} max={options.maxDurationSeconds}
                    step={1} value={spec.durationSeconds} aria-label="视频时长（秒）"
                    onChange={(event) => onSpecChange({ ...spec, durationSeconds: Number(event.target.value) })} />
                  <div className="spec-scale" aria-hidden="true">
                    <span>{options.minDurationSeconds}</span><span>{options.maxDurationSeconds}</span>
                  </div>
                </div>
              </div>
            )}
          </div>

    </fieldset>
  )
}
