import { useRef } from 'react'
import type { VideoExample } from './videoExamples'
import { videoExamples } from './videoExamples'

function ExampleCard({ example, onUse }: { example: VideoExample; onUse: (prompt: string) => void }) {
  const mediaRef = useRef<HTMLVideoElement>(null)

  function play() {
    void mediaRef.current?.play().catch(() => undefined)
  }

  function reset() {
    const media = mediaRef.current
    if (!media) return
    media.pause()
    media.currentTime = 0
  }

  return (
    <article className="video-card">
      <div className="video-card-media" onMouseEnter={play} onMouseLeave={reset}>
        <video ref={mediaRef} src={example.src} muted loop playsInline preload="metadata" />
        <button type="button" className="video-use" onClick={() => onUse(example.prompt)}>
          使用提示词 <span aria-hidden="true">→</span>
        </button>
      </div>
      <p className="video-card-title">{example.title}</p>
    </article>
  )
}

export function VideoGallery({ onUsePrompt }: { onUsePrompt: (prompt: string) => void }) {
  return (
    <section className="video-gallery" aria-labelledby="video-examples-title">
      <div className="section-heading">
        <h2 id="video-examples-title">视频示例</h2>
        <span className="template-count">悬停可预览，点击“使用提示词”直接复用</span>
      </div>
      <div className="video-grid">
        {videoExamples.map((example) => (
          <ExampleCard key={example.id} example={example} onUse={onUsePrompt} />
        ))}
      </div>
    </section>
  )
}
