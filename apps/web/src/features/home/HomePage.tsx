import { useEffect, useRef, useState } from 'react'
import type { ProjectType } from '../../api/capabilities'
import { describeProvider } from '../../api/provider'
import { Icon } from '../../components/Icon'
import type { IconName } from '../../components/Icon'
import { ProjectDetail } from '../projects/ProjectDetail'
import { ProjectError } from '../projects/ProjectError'
import { ProjectList } from '../projects/ProjectList'
import { useProjectRoute } from '../projects/navigation'
import { useSession } from '../projects/useSession'
import { ChatPage } from '../chat/ChatPage'
import { SlidesWorkbench } from '../slides/SlidesWorkbench'
import { TemplateGallery } from '../templates/TemplateGallery'
import { VideoWorkbench } from '../video/VideoWorkbench'
import { WebTemplateGallery } from '../web/WebTemplateGallery'
import { DraftComposer } from './DraftComposer'
import { useCapabilities } from './useCapabilities'
import { useProvider } from './useProvider'

const outputs: { type: ProjectType; label: string; icon: IconName; placeholder: string; stage: string }[] = [
  { type: 'WEB', label: '网页', icon: 'web', placeholder: '例如：做一个简洁的个人作品集，展示我的设计、经历和联系方式…', stage: '第一阶段' },
  { type: 'GAME', label: '游戏', icon: 'game', placeholder: '例如：做一个可以在浏览器里玩的小游戏，有计分和重新开始…', stage: '第一阶段' },
  { type: 'SLIDES', label: '幻灯片', icon: 'slides', placeholder: '例如：准备一套产品介绍幻灯片，先帮我梳理演示大纲…', stage: '第三阶段' },
  { type: 'VIDEO', label: '视频', icon: 'video', placeholder: '例如：生成一段晨光穿过森林的短片，画面柔和、镜头缓慢前进…', stage: '第四阶段' },
]

const agents = [
  ['Mike', '#ffe4bf'], ['Emma', '#fff0b0'], ['Bob', '#e6e0d1'], ['Alex', '#fbd9e8'],
  ['David', '#dce2ff'], ['Iris', '#d4ecff'], ['Sarah', '#d7efdc'], ['Adrian', '#e9d9ff'],
] as const

function initialType(): ProjectType {
  const requested = new URLSearchParams(window.location.search).get('type')?.toUpperCase()
  return requested === 'GAME' || requested === 'SLIDES' || requested === 'VIDEO' ? requested : 'WEB'
}

export function HomePage() {
  const [type, setType] = useState<ProjectType>(initialType)
  const route = useProjectRoute()
  const [menuOpen, setMenuOpen] = useState(false)
  const menuButtonRef = useRef<HTMLButtonElement>(null)
  const closeMenuButtonRef = useRef<HTMLButtonElement>(null)
  const sidebarRef = useRef<HTMLElement>(null)
  const { state, retry } = useCapabilities()
  const managementAvailable = state.status === 'ready' && state.capabilities.projectManagementAvailable
  const session = useSession(managementAvailable)
  const provider = useProvider()
  const canManage = managementAvailable && session.state.status === 'ready'
  const output = outputs.find((item) => item.type === type) ?? outputs[0]
  const providerInfo = type === 'VIDEO' && provider.state.status === 'ready'
    ? describeProvider(provider.state.provider)
    : null
  const serviceMessage = state.status === 'loading'
    ? '正在连接创作服务…'
    : state.status === 'error'
      ? state.message
      : !managementAvailable
        ? '当前服务尚未开放项目管理。'
        : session.state.status === 'loading' || session.state.status === 'idle'
          ? '正在连接匿名创作空间…'
          : session.state.status === 'error'
            ? '匿名创作空间连接失败，请重试。'
            : '项目草稿可以保存；进入项目后即可发起生成，模型未就绪时会如实报错。'

  useEffect(() => {
    setMenuOpen(false)
  }, [route.kind])

  useEffect(() => {
    if (!menuOpen) return
    const mobileViewport = window.matchMedia('(max-width: 700px)')
    if (!mobileViewport.matches) {
      setMenuOpen(false)
      return
    }

    const sidebar = sidebarRef.current
    const menuButton = menuButtonRef.current
    if (!sidebar) return
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    closeMenuButtonRef.current?.focus()

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault()
        setMenuOpen(false)
        return
      }
      if (event.key !== 'Tab' || !sidebar) return
      const items = sidebar.querySelectorAll<HTMLElement>('a[href], button:not([disabled])')
      const first = items.item(0)
      const last = items.item(items.length - 1)
      if (!first || !last) return
      const outside = !sidebar.contains(document.activeElement)
      if (event.shiftKey && (document.activeElement === first || outside)) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && (document.activeElement === last || outside)) {
        event.preventDefault()
        first.focus()
      }
    }

    function handleViewportChange() {
      if (!mobileViewport.matches) setMenuOpen(false)
    }

    document.addEventListener('keydown', handleKeyDown)
    mobileViewport.addEventListener('change', handleViewportChange)
    return () => {
      document.removeEventListener('keydown', handleKeyDown)
      mobileViewport.removeEventListener('change', handleViewportChange)
      document.body.style.overflow = previousOverflow
      if (mobileViewport.matches) menuButton?.focus()
    }
  }, [menuOpen])

  function selectOutput(nextType: ProjectType) {
    setType(nextType)
    window.location.hash = 'home'
    setMenuOpen(false)
  }

  return (
    <div className="app-shell">
      <header className="mobile-header" inert={menuOpen}>
        <button ref={menuButtonRef} type="button" className="icon-button" aria-label={menuOpen ? '收起导航' : '展开导航'}
          aria-expanded={menuOpen} aria-controls="sidebar" onClick={() => setMenuOpen(!menuOpen)}>
          <Icon name="menu" />
        </button>
        <span className="wordmark">Atoms <small>Demo</small></span>
        <span className="mobile-badge">免登录</span>
      </header>

      {menuOpen && <button type="button" className="menu-backdrop" tabIndex={-1} aria-hidden="true" onClick={() => setMenuOpen(false)} />}
      <aside ref={sidebarRef} id="sidebar" className={`sidebar ${menuOpen ? 'is-open' : ''}`}
        role={menuOpen ? 'dialog' : undefined} aria-modal={menuOpen ? true : undefined}
        aria-label={menuOpen ? '主要导航' : undefined}>
        <button ref={closeMenuButtonRef} type="button" className="icon-button sidebar-close"
          aria-label="关闭导航" onClick={() => setMenuOpen(false)}><Icon name="close" /></button>
        <a className="brand" href="#home" onClick={() => setMenuOpen(false)}>
          <span className="brand-mark" aria-hidden="true">a</span>
          <span className="wordmark">Atoms <small>Demo</small></span>
        </a>
        <div className="workspace-label"><span className="visitor-avatar">访</span><span>我的创作空间<small>匿名访问 · 无需登录</small></span></div>
        <nav aria-label="主要导航">
          <button type="button" className={`nav-item ${route.kind === 'home' && type === 'WEB' ? 'selected' : ''}`}
            aria-current={route.kind === 'home' && type === 'WEB' ? 'page' : undefined} onClick={() => selectOutput('WEB')}>
            <Icon name="home" /> 首页
          </button>
          {outputs.filter((item) => item.type !== 'WEB').map((item) => (
            <button type="button" key={item.type} className={`nav-item ${route.kind === 'home' && type === item.type ? 'selected' : ''}`}
              aria-current={route.kind === 'home' && type === item.type ? 'page' : undefined} onClick={() => selectOutput(item.type)}>
              <Icon name={item.icon} /> {item.label}
              <small>{item.type === 'GAME' ? '游戏生成与修改' : item.type === 'SLIDES' ? '模板与生成可用' : '示例与流程可用'}</small>
            </button>
          ))}
          <button type="button" className={`nav-item ${route.kind === 'projects' || route.kind === 'detail' ? 'selected' : ''}`}
            aria-current={route.kind === 'projects' || route.kind === 'detail' ? 'page' : undefined}
            onClick={() => { window.location.hash = 'projects'; setMenuOpen(false) }}>
            <Icon name="folder" /> 项目
          </button>
        </nav>
        <div className="sidebar-recent"><span>我的草稿</span><p>已保存项目仅属于当前匿名会话。</p><a href="#projects" onClick={() => setMenuOpen(false)}>查看项目</a></div>
        <div className="sidebar-footer"><span className="status-dot" />工作台开发中<small>从一个想法开始</small></div>
      </aside>

      <main id="home" className={`main-content ${route.kind === 'chat' ? 'chat-main' : ''}`} inert={menuOpen}>
        {route.kind !== 'chat' && <div className="topbar"><span>创作工作台</span><span className="outline-badge">免登录体验</span></div>}
        {session.state.status === 'error' && <ProjectError error={session.state.error} onRetry={session.retry} onReconnect={session.retry} />}
        {route.kind !== 'home' && route.kind !== 'chat' && (
          <div className={`service-notice page-service ${state.status === 'error' ? 'error' : ''}`} role="status">
            <p>{serviceMessage}</p>
            {state.status === 'error' && <button type="button" className="text-button" onClick={retry}>重新连接</button>}
          </div>
        )}
        {route.kind === 'home' && type === 'SLIDES' ? (
          <>
            <SlidesWorkbench enabled={canManage} onReconnect={session.retry} />
            <section className="recent-section" aria-labelledby="recent-title">
              <div className="section-heading"><h2 id="recent-title">最近项目</h2><a className="text-button" href="#projects">全部项目</a></div>
              <ProjectList recent enabled={canManage} onReconnect={session.retry} />
            </section>
          </>
        ) : route.kind === 'home' && type === 'VIDEO' ? (
          <>
            <VideoWorkbench enabled={canManage} onReconnect={session.retry} />
            <section className="recent-section" aria-labelledby="recent-title">
              <div className="section-heading"><h2 id="recent-title">最近项目</h2><a className="text-button" href="#projects">全部项目</a></div>
              <ProjectList recent enabled={canManage} onReconnect={session.retry} />
            </section>
          </>
        ) : route.kind === 'home' ? (
          <>
            <section className="hero" aria-labelledby="hero-title">
              <span className="stage-badge">创作空间 · 第一阶段</span>
              <div className="agent-row" aria-label="计划接入的八位创作伙伴，当前尚未运行">
                {agents.map(([name, color]) => <span key={name} className="agent-avatar" title={`${name} · 待接入`} style={{ backgroundColor: color }}>{name.slice(0, 1)}</span>)}
              </div>
              <h1 id="hero-title">输入想法，产出作品。</h1>
              <p className="hero-description">从网页到视频，让灵感有一个开始。</p>

              <DraftComposer type={type} label={output.label} placeholder={output.placeholder} enabled={canManage} onReconnect={session.retry} />
              <div className="output-options" role="group" aria-label="选择创作类型">
                {outputs.map((item) => (
                  <button key={item.type} type="button" aria-pressed={type === item.type}
                    className={`output-option ${type === item.type ? 'active' : ''}`} onClick={() => selectOutput(item.type)}>
                    <Icon name={item.icon} />{item.label}
                  </button>
                ))}
              </div>
              <div className={`service-notice ${state.status === 'error' ? 'error' : ''}`} role="status" aria-live="polite">
                <span className={`status-dot ${state.status}`} />
                <div><p id="service-message">{serviceMessage}</p><small>{output.label}生成计划于{output.stage}开放。清除 Cookie 或匿名会话过期后，原项目可能无法找回。</small>
                  {providerInfo && (
                    <small className={`provider-line ${providerInfo.warning ? 'warn' : ''}`}>{providerInfo.text}</small>
                  )}
                </div>
                {state.status === 'error' && <button type="button" className="text-button" onClick={retry}>重新连接</button>}
              </div>
            </section>

            {type === 'WEB' && <WebTemplateGallery canClone={canManage} />}
            {type === 'GAME' && <TemplateGallery canClone={canManage} />}

            <section className="recent-section" aria-labelledby="recent-title">
              <div className="section-heading"><h2 id="recent-title">最近项目</h2><a className="text-button" href="#projects">全部项目</a></div>
              <ProjectList recent enabled={canManage} onReconnect={session.retry} />
            </section>
          </>
        ) : route.kind === 'projects' ? (
          <section className="project-page">
            <span className="stage-badge">我的空间</span><h1>项目</h1><p className="hero-description">在这里管理和继续你的创作。</p>
            <div className="section-heading"><h2>已保存的草稿</h2><a className="secondary-button" href="#home">新建草稿</a></div>
            <ProjectList key={route.page} page={route.page} enabled={canManage} onReconnect={session.retry} />
          </section>
        ) : route.kind === 'detail' ? (
          <section className="project-page"><ProjectDetail key={route.id} id={route.id} enabled={canManage} onReconnect={session.retry} /></section>
        ) : route.kind === 'chat' ? (
          <ChatPage key={route.id} id={route.id} enabled={canManage} onReconnect={session.retry} />
        ) : (
          <section className="project-page"><h1>页面不存在</h1><p>请检查项目地址，或返回项目列表。</p><a href="#projects">返回项目列表</a></section>
        )}
        {route.kind !== 'chat' && <footer className="page-footer">Atoms Demo · 核心体验复刻项目</footer>}
      </main>
    </div>
  )
}
