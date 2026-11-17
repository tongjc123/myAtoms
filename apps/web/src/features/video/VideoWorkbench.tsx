import { useCallback, useEffect, useRef, useState } from 'react'
import { fetchVideoOptions } from '../../api/videos'
import type { VideoOptions, VideoSpec } from '../../api/videos'
import { VideoComposer } from './VideoComposer'
import { VideoGallery } from './VideoGallery'

export function VideoWorkbench({ enabled, onReconnect }: { enabled: boolean; onReconnect: () => void }) {
  const [options, setOptions] = useState<VideoOptions | null>(null)
  const [optionsError, setOptionsError] = useState<string | null>(null)
  const [spec, setSpec] = useState<VideoSpec | null>(null)
  const [prompt, setPrompt] = useState('')
  const composerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const controller = new AbortController()
    fetchVideoOptions(controller.signal)
      .then(setOptions)
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) {
          setOptionsError(cause instanceof Error ? cause.message : '暂时无法读取视频选项。')
        }
      })
    return () => controller.abort()
  }, [])

  const applyPrompt = useCallback((value: string) => {
    setPrompt(value)
    composerRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    composerRef.current?.querySelector('textarea')?.focus()
  }, [])

  const providerReady = options?.providerConfigured && options.providerState === 'ACTIVATED'
  const providerMessage = options == null ? null
    : !options.providerConfigured
      ? '服务端尚未配置视频模型密钥，发起生成会被明确拒绝。'
      : options.providerState === 'ACTIVATED'
        ? null
        : `当前默认模型状态：${options.providerDetail}生成仍会尝试发起，失败时会如实说明原因。`

  return (
    <>
      <section className="hero video-hero" aria-labelledby="video-hero-title">
        <video className="video-hero-media" src="/videos/hero-racetrack.mp4"
          autoPlay muted loop playsInline aria-hidden="true" />
        <div className="video-hero-scrim" aria-hidden="true" />
        <div className="video-hero-content">
          <span className="stage-badge">视频 · 第四阶段</span>
          <h1 id="video-hero-title">让灵感，成为视频</h1>
          <p className="hero-description">描述画面，挑选模型与规格，生成过程全程可跟踪、可停止。</p>
          <div ref={composerRef}>
            <VideoComposer enabled={enabled && !optionsError} options={options}
              spec={spec} onSpecChange={setSpec} prompt={prompt} onPromptChange={setPrompt}
              onReconnect={onReconnect} />
          </div>
          {optionsError && <p className="video-provider-note warn" role="alert">{optionsError}</p>}
          {providerMessage && !providerReady && (
            <p className="video-provider-note warn" role="status">{providerMessage}</p>
          )}
        </div>
      </section>

      <VideoGallery onUsePrompt={applyPrompt} />
    </>
  )
}
