// Run against a local Vite server and an isolated Chromium debugging instance.
// These are frontend contract fixtures, not MySQL integration evidence.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'

const origin = process.env.WEB_TEST_ORIGIN ?? 'http://127.0.0.1:5173'
const debuggerOrigin = process.env.WEB_TEST_DEBUGGER ?? 'http://127.0.0.1:9225'
const target = await (await fetch(`${debuggerOrigin}/json/new?${encodeURIComponent(origin)}`, { method: 'PUT' })).json()
const socket = new WebSocket(target.webSocketDebuggerUrl)
await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject })
const pending = new Map()
let sequence = 0
let loadEvents = 0
let sessionCalls = 0
let failCreateOnce = true
let conflictOnce = true
let invalidProject = false
let invalidRevision = false
let malformedCapabilities = false
let rejectCsrfOnce = false
let slowId
let slowRequest
const projects = new Map()
const receipts = new Map()
const mutationRequests = []
const exceptions = []
const checks = []
const session = { sessionId: randomUUID(), csrfToken: 'frontend-test-token', expiresAt: '2099-01-01T00:00:00Z' }
const capabilities = {
  generationAvailable: false, projectManagementAvailable: true, reasonCode: 'PIPELINE_NOT_IMPLEMENTED',
  message: '项目管理已连接，生成尚未开放。', projectTypes: ['WEB', 'GAME', 'SLIDES', 'VIDEO'],
}

function call(method, params = {}) {
  const id = ++sequence
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)) }, 12_000)
    pending.set(id, {
      resolve: (result) => { clearTimeout(timeout); resolve(result) },
      reject: (error) => { clearTimeout(timeout); reject(error) },
    })
    socket.send(JSON.stringify({ id, method, params }))
  })
}

async function fulfill(requestId, status, body, cookie = false) {
  await call('Fetch.fulfillRequest', {
    requestId, responseCode: status,
    responseHeaders: [
      { name: 'Content-Type', value: 'application/json' },
      { name: 'Cache-Control', value: 'no-store' },
      ...(cookie ? [{ name: 'Set-Cookie', value: 'atoms_test=fixture-only; Path=/; HttpOnly; SameSite=Strict' }] : []),
    ],
    ...(status === 204 ? {} : { body: Buffer.from(JSON.stringify(body)).toString('base64') }),
  })
}

function error(code, message) {
  return { code, message, retryable: false, requestId: randomUUID() }
}

async function intercept({ requestId, request }) {
  const url = new URL(request.url)
  if (url.pathname === '/api/system/capabilities') {
    return fulfill(requestId, 200, malformedCapabilities ? { generationAvailable: false } : capabilities)
  }
  if (url.pathname === '/api/session') {
    sessionCalls++
    await new Promise((resolve) => setTimeout(resolve, 80))
    return fulfill(requestId, 200, session, true)
  }
  const id = url.pathname.split('/')[3]
  if (request.method === 'GET') {
    if (!id) {
      const page = Number(url.searchParams.get('page'))
      const pageSize = Number(url.searchParams.get('pageSize'))
      const all = [...projects.values()]
      return fulfill(requestId, 200, { items: all.slice(page * pageSize, (page + 1) * pageSize), page, pageSize, total: all.length })
    }
    if (id === slowId) { slowRequest = requestId; return }
    const project = projects.get(id)
    return project
      ? fulfill(requestId, 200, { project: invalidProject ? { ...project, status: 'GENERATED' }
        : invalidRevision ? { ...project, revision: 0 } : project })
      : fulfill(requestId, 404, error('PROJECT_NOT_FOUND', '项目不存在。'))
  }
  const headers = Object.fromEntries(Object.entries(request.headers).map(([key, value]) => [key.toLowerCase(), value]))
  assert.match(headers['idempotency-key'], /^[0-9a-f-]{36}$/)
  assert.equal(headers['x-csrf-token'], session.csrfToken)
  assert.equal(headers['content-type'], 'application/json')
  assert.equal(headers.origin, origin)
  assert.match(headers.cookie, /atoms_test=fixture-only/)
  mutationRequests.push({ method: request.method, key: headers['idempotency-key'], body: request.postData })
  const receipt = receipts.get(headers['idempotency-key'])
  if (receipt) return fulfill(requestId, receipt.status, receipt.body)
  if (rejectCsrfOnce) {
    rejectCsrfOnce = false
    return fulfill(requestId, 403, error('CSRF_REJECTED', '会话校验失败。'))
  }
  const body = JSON.parse(request.postData)
  if (request.method === 'POST') {
    const project = {
      ...body, id: randomUUID(), status: 'DRAFT', revision: 1,
      createdAt: '2026-11-12T02:00:00Z', updatedAt: '2026-11-12T02:00:00Z',
    }
    projects.set(project.id, project)
    receipts.set(headers['idempotency-key'], { status: 201, body: { project } })
    if (failCreateOnce) {
      failCreateOnce = false
      await new Promise((resolve) => setTimeout(resolve, 150))
      return call('Fetch.failRequest', { requestId, errorReason: 'Failed' })
    }
    return fulfill(requestId, 201, { project })
  }
  const project = projects.get(id)
  if (!project) return fulfill(requestId, 404, error('PROJECT_NOT_FOUND', '项目不存在。'))
  if (request.method === 'PATCH' && conflictOnce) {
    conflictOnce = false
    project.name = '另一页面更新的名称'
    project.revision++
  }
  if (body.expectedRevision !== project.revision) {
    return fulfill(requestId, 409, error('VERSION_CONFLICT', '项目版本已变化。'))
  }
  if (request.method === 'DELETE') {
    projects.delete(id)
    receipts.set(headers['idempotency-key'], { status: 204 })
    return fulfill(requestId, 204)
  }
  project.name = body.name
  project.revision++
  receipts.set(headers['idempotency-key'], { status: 200, body: { project: { ...project } } })
  return fulfill(requestId, 200, { project })
}

socket.onmessage = ({ data }) => {
  const message = JSON.parse(data)
  if (message.method === 'Page.loadEventFired') loadEvents++
  if (message.method === 'Fetch.requestPaused') {
    intercept(message.params).catch((failure) => {
      // StrictMode and route changes can abort a request before its fixture reply.
      if (!failure.message.includes('Invalid InterceptionId')) exceptions.push(failure.message)
    })
  }
  if (message.method === 'Runtime.exceptionThrown') exceptions.push(message.params.exceptionDetails.text)
  const entry = pending.get(message.id)
  if (entry) {
    pending.delete(message.id)
    if (message.error) entry.reject(new Error(JSON.stringify(message.error)))
    else entry.resolve(message.result)
  }
}

async function evaluate(expression) {
  const result = await call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails))
  return result.result.value
}

async function waitFor(expression) {
  for (let attempt = 0; attempt < 80; attempt++) {
    if (exceptions.length) throw new Error(exceptions.join('\n'))
    if (await evaluate(expression)) return
    await new Promise((resolve) => setTimeout(resolve, 75))
  }
  throw new Error(`Timed out: ${expression}`)
}

async function check(name, expression) {
  assert.equal(await evaluate(expression), true, name)
  checks.push(name)
}

async function reload() {
  const previous = loadEvents
  await call('Page.reload')
  for (let attempt = 0; attempt < 80; attempt++) {
    if (loadEvents > previous) return
    await new Promise((resolve) => setTimeout(resolve, 75))
  }
  throw new Error('Page reload did not finish')
}

async function input(selector, value, tag = 'HTMLInputElement') {
  await evaluate(`(() => {
    const element = document.querySelector(${JSON.stringify(selector)});
    Object.getOwnPropertyDescriptor(${tag}.prototype, 'value').set.call(element, ${JSON.stringify(value)});
    element.dispatchEvent(new Event('input', { bubbles: true }));
    element.dispatchEvent(new Event('change', { bubbles: true }));
  })()`)
}

async function clickText(text) {
  await evaluate(`Array.from(document.querySelectorAll('button')).find(button => button.textContent === ${JSON.stringify(text)}).click()`)
}

async function key(key, code) {
  await call('Input.dispatchKeyEvent', { type: 'keyDown', key, code: key, windowsVirtualKeyCode: code })
}

try {
  await call('Runtime.enable')
  await call('Page.enable')
  await call('Fetch.enable', { patterns: [{ urlPattern: `${origin}/api/*`, requestStage: 'Request' }] })
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })
  await call('Page.navigate', { url: `${origin}/#home` })
  await waitFor('document.querySelector("#service-message")?.textContent.includes("草稿可以保存")')
  assert.equal(sessionCalls, 1, 'single shared bootstrap')
  checks.push('single anonymous bootstrap')
  const beforeConcurrentBootstrap = sessionCalls
  await check('concurrent consumers share session initialization', `(async () => {
    const session = await import('/src/api/session.ts');
    session.invalidateSession();
    const first = session.ensureSession(), second = session.ensureSession();
    await Promise.all([first, second]);
    return first === second;
  })()`)
  assert.equal(sessionCalls, beforeConcurrentBootstrap + 1)
  await check('empty draft disabled', 'document.querySelector(".save-draft").disabled')
  await check('desktop layout', 'document.documentElement.scrollWidth <= innerWidth')
  for (const label of ['游戏', '幻灯片', '视频', '网页']) {
    await evaluate(`Array.from(document.querySelectorAll(".output-option")).find(button => button.textContent === ${JSON.stringify(label)}).click()`)
    await waitFor(`document.querySelector(".output-option[aria-pressed=true]")?.textContent === ${JSON.stringify(label)}`)
  }
  checks.push('four type tabs')
  await input('#project-name', '浏览器草稿')
  await input('#prompt', '保存真实需求；不生成作品。', 'HTMLTextAreaElement')
  await input('#project-mode', 'PLAN', 'HTMLSelectElement')
  await evaluate('document.querySelector(".draft-form").requestSubmit(); document.querySelector(".draft-form").requestSubmit()')
  await waitFor('document.querySelector(".project-error")?.textContent.includes("无法连接")')
  assert.equal(mutationRequests.length, 1, 'duplicate submit prevented')
  await check('failed save preserves input', 'document.querySelector("#project-name").value === "浏览器草稿"')
  await clickText('保存项目草稿')
  await waitFor('document.querySelector(".project-detail h1")?.textContent === "浏览器草稿"')
  assert.equal(mutationRequests[0].key, mutationRequests[1].key, 'ambiguous retry reuses key')
  assert.equal(projects.size, 1, 'retry does not create duplicate')
  checks.push('idempotent retry and duplicate prevention')
  await check('explicit draft and generation unavailable', 'document.querySelector(".project-detail").textContent.includes("DRAFT") && document.querySelector("[aria-describedby=generation-status]").disabled')
  await check('saved plan mode', 'document.querySelector(".project-facts").textContent.includes("规划")')
  const id = [...projects.keys()][0]
  await reload()
  await waitFor('document.querySelector(".project-detail h1")?.textContent === "浏览器草稿"')
  await check('detail survives refresh', `location.hash === "#projects/${id}"`)
  await input('#rename-project', '保留我的输入')
  await clickText('保存名称')
  await waitFor('document.querySelector(".project-error")?.textContent.includes("项目版本已变化")')
  await check('conflict preserves input and blocks stale write', 'document.querySelector("#rename-project").value === "保留我的输入" && document.querySelector(".rename-form button").disabled')
  await clickText('读取最新项目')
  await waitFor('document.querySelector(".project-detail h1")?.textContent === "另一页面更新的名称"')
  await check('refresh preserves edit', 'document.querySelector("#rename-project").value === "保留我的输入" && !document.querySelector(".rename-form button").disabled')
  await clickText('保存名称')
  await waitFor('document.querySelector(".project-detail h1")?.textContent === "保留我的输入"')
  checks.push('conflict recovery saves new revision')
  rejectCsrfOnce = true
  await input('#rename-project', '会话恢复后的名称')
  await clickText('保存名称')
  await waitFor('document.querySelector(".project-error")?.textContent.includes("会话校验失败")')
  await clickText('重新连接会话')
  await waitFor('document.querySelector("#rename-project")?.value === "会话恢复后的名称"')
  await clickText('保存名称')
  await waitFor('document.querySelector(".project-detail h1")?.textContent === "会话恢复后的名称"')
  checks.push('CSRF recovery preserves input')
  await clickText('删除项目…')
  await check('delete confirmation and initial focus', 'document.activeElement.textContent === "取消" && Boolean(document.querySelector(".delete-confirmation"))')
  await key('Escape', 27)
  await waitFor('!document.querySelector(".delete-confirmation")')
  await check('delete cancellation restores focus', 'document.activeElement.textContent === "删除项目…"')
  assert.equal(projects.size, 1)
  await clickText('删除项目…')
  await clickText('确认删除草稿')
  await waitFor('location.hash === "#projects" && document.querySelector(".empty-projects h3")?.textContent.includes("第一个")')
  assert.equal(projects.size, 0)
  checks.push('confirmed deletion removes project')
  await evaluate(`location.hash = "projects/${id}"`)
  await waitFor('document.querySelector(".project-error")?.textContent.includes("项目不存在")')
  checks.push('deleted detail has explicit error')

  for (let index = 0; index < 21; index++) {
    const projectId = randomUUID()
    projects.set(projectId, {
      id: projectId, name: `分页草稿 ${index + 1}`, prompt: '测试需求'.repeat(100), type: 'WEB',
      mode: 'BUILD', status: 'DRAFT', revision: 1, createdAt: '2026-11-12T02:00:00Z', updatedAt: '2026-11-12T02:00:00Z',
    })
  }
  await evaluate('location.hash = "projects"')
  await waitFor('document.querySelectorAll(".project-card").length === 20')
  await clickText('下一页')
  await waitFor('document.querySelectorAll(".project-card").length === 1')
  await check('pagination and last-page boundary', 'location.hash === "#projects?page=1" && Array.from(document.querySelectorAll("button")).find(button => button.textContent === "下一页").disabled')
  await evaluate('location.hash = "projects?page=10000"')
  await waitFor('document.querySelector(".empty-projects h3")?.textContent === "这一页已没有项目"')
  checks.push('maximum backend page remains accessible')
  await evaluate('location.hash = "projects?page=10001"')
  await waitFor('document.querySelector(".project-page h1")?.textContent === "页面不存在"')
  checks.push('route rejects pages above backend limit')
  const [slowProject, fastProject] = [...projects.values()]
  slowId = slowProject.id
  await evaluate(`location.hash = "projects/${slowProject.id}"`)
  for (let attempt = 0; !slowRequest && attempt < 30; attempt++) await new Promise((resolve) => setTimeout(resolve, 50))
  assert.ok(slowRequest)
  await evaluate(`location.hash = "projects/${fastProject.id}"`)
  await waitFor(`document.querySelector(".project-detail h1")?.textContent === ${JSON.stringify(fastProject.name)}`)
  try { await fulfill(slowRequest, 200, { project: slowProject }) } catch (failure) {
    if (!failure.message.includes('Invalid InterceptionId')) throw failure
  }
  await check('late detail cannot replace current route', `document.querySelector(".project-detail h1").textContent === ${JSON.stringify(fastProject.name)}`)
  invalidProject = true
  await clickText('刷新项目')
  await waitFor('document.querySelector(".project-error")?.textContent.includes("无法识别")')
  checks.push('invalid project payload rejected')
  invalidProject = false
  invalidRevision = true
  await clickText('重新读取')
  await waitFor('document.querySelector(".project-error")?.textContent.includes("无法识别")')
  checks.push('zero project revision rejected')
  invalidRevision = false

  await evaluate('location.hash = "home"')
  await waitFor('Boolean(document.querySelector("#project-name"))')
  await call('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
  await check('phone layout', 'document.documentElement.scrollWidth <= innerWidth')
  await evaluate('document.querySelector(".mobile-header button").click()')
  await waitFor('document.activeElement === document.querySelector(".sidebar-close")')
  await evaluate('Array.from(document.querySelectorAll(".sidebar a[href], .sidebar button:not([disabled])")).at(-1).focus()')
  await key('Tab', 9)
  await check('mobile focus trap', 'document.activeElement === document.querySelector(".sidebar-close") && document.querySelector("main").inert')
  await key('Escape', 27)
  await waitFor('!document.querySelector(".sidebar").classList.contains("is-open")')
  await check('mobile escape restores focus', 'document.activeElement === document.querySelector(".mobile-header button") && !document.querySelector("main").inert')
  await check('no credentials in browser storage', 'localStorage.length === 0 && sessionStorage.length === 0 && !document.cookie.includes("atoms_test")')
  malformedCapabilities = true
  await reload()
  await waitFor('document.querySelector("#service-message")?.textContent.includes("无法识别")')
  await check('invalid capabilities fail closed', 'document.querySelector(".save-draft").disabled')
  malformedCapabilities = false
  await clickText('重新连接')
  await waitFor('document.querySelector("#service-message")?.textContent.includes("草稿可以保存")')
  checks.push('capability retry recovers')
  assert.deepEqual(exceptions, [])
  console.log(`PASS: ${checks.length} frontend browser checks (contract fixtures; no database assertions).`)
  for (const name of checks) console.log(`  ${name}`)
} finally {
  await call('Fetch.disable').catch(() => {})
  socket.close()
  await fetch(`${debuggerOrigin}/json/close/${target.id}`).catch(() => {})
}
