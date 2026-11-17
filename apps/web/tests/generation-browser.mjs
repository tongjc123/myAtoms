// Bounded frontend contract fixtures. Every /api request is intercepted; no provider is called.
// Start an isolated Chromium debugger on 9227 (never attach to a user's browser).
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'

const origin = process.env.WEB_TEST_ORIGIN ?? 'http://127.0.0.1:5173'
const debuggerOrigin = process.env.WEB_TEST_DEBUGGER ?? 'http://127.0.0.1:9227'
const target = await (await fetch(`${debuggerOrigin}/json/new?${encodeURIComponent('about:blank')}`, { method: 'PUT' })).json()
const socket = new WebSocket(target.webSocketDebuggerUrl)
await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject })
let sequence = 0
const pending = new Map()
const exceptions = []
const checks = []
const requests = []
const receipts = new Map()
const snapshots = new Map()
const date = '2026-11-16T00:00:00Z'
const projects = Object.fromEntries(['WEB', 'GAME', 'SLIDES', 'VIDEO'].map(type => [type, {
  id: randomUUID(), type, name: `${type} fixture`, prompt: `Original ${type} request`,
  mode: 'BUILD', status: 'DRAFT', revision: 1, createdAt: date, updatedAt: date,
  currentVersion: null, sourceTemplate: null,
}]))
let outcome = 'success'
let streamMode = 'complete'
let streamCalls = 0
function call(method, params = {}) {
  const id = ++sequence
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)) }, 10_000)
    pending.set(id, {
      resolve: result => { clearTimeout(timer); resolve(result) },
      reject: error => { clearTimeout(timer); reject(error) },
    })
    socket.send(JSON.stringify({ id, method, params }))
  })
}
function error(code, message) { return { code, message, retryable: true, requestId: randomUUID() } }
function fulfill(requestId, responseCode, body) {
  return call('Fetch.fulfillRequest', {
    requestId, responseCode, responseHeaders: [{ name: 'Content-Type', value: 'application/json' }],
    body: Buffer.from(JSON.stringify(body)).toString('base64'),
  })
}
function advance(project) {
  project.currentVersion = {
    id: randomUUID(), number: (project.currentVersion?.number ?? 0) + 1,
    kind: 'GENERATED', summary: 'Browser fixture only', createdAt: date,
  }
  project.status = 'READY'
  project.revision++
}
async function intercept({ requestId, request }) {
  const path = new URL(request.url).pathname
  if (path === '/api/system/capabilities') return fulfill(requestId, 200, {
    generationAvailable: true, projectManagementAvailable: true, reasonCode: 'READY', message: 'Fixture',
    projectTypes: Object.keys(projects),
  })
  if (path === '/api/session') return fulfill(requestId, 200, {
    sessionId: randomUUID(), csrfToken: 'fixture-csrf', expiresAt: '2099-01-01T00:00:00Z',
  })
  if (path === '/api/system/provider') return fulfill(requestId, 503, error('NOT_ACTIVATED', 'Fixture model unavailable'))
  if (path === '/api/video/options') return fulfill(requestId, 200, {
    models: [{ key: 'fixture-video', label: '测试模型', available: true, description: 'Fixture only', badge: null }],
    defaultModel: 'fixture-video', defaultRatio: null, defaultResolution: '720p', defaultDurationSeconds: 5,
    minDurationSeconds: 3, maxDurationSeconds: 10, ratios: ['16:9', '9:16'], resolutions: ['720p', '1080p'],
    providerConfigured: true, providerState: 'NOT_ACTIVATED', providerDetail: '模型尚未开通（浏览器测试）',
  })
  const project = Object.values(projects).find(item => path.split('/')[3] === item.id)
  if (!project) return fulfill(requestId, 404, error('FIXTURE_NOT_FOUND', `Unconfigured fixture: ${path}`))
  if (path.endsWith('/events')) {
    streamCalls++
    if (streamMode === 'complete') snapshots.get(project.id).run.status = 'SUCCEEDED'
    return call('Fetch.fulfillRequest', {
      requestId, responseCode: 200,
      responseHeaders: [{ name: 'Content-Type', value: 'text/event-stream' }],
      body: Buffer.from(': fixture EOF\n\n').toString('base64'),
    })
  }
  if (path.endsWith('/runs/latest')) return fulfill(requestId, snapshots.has(project.id) ? 200 : 404,
    snapshots.get(project.id) ?? error('RUN_NOT_FOUND', 'No run'))
  if (path.endsWith('/runs') && request.method === 'POST') {
    const headers = Object.fromEntries(Object.entries(request.headers).map(([key, value]) => [key.toLowerCase(), value]))
    const payload = JSON.parse(request.postData)
    const key = headers['idempotency-key']
    requests.push({ projectId: project.id, payload, key })
    assert.match(key ?? '', /^[\da-f-]{36}$/i)
    assert.equal(headers['x-csrf-token'], 'fixture-csrf')
    if (receipts.has(key)) return fulfill(requestId, 200, receipts.get(key))
    if (outcome === 'provider') {
      outcome = 'success'
      return fulfill(requestId, 503, error('PROVIDER_MODEL_NOT_ACTIVATED', '文本模型尚未开通'))
    }
    const runId = randomUUID()
    advance(project)
    const artifact = {
      kind: project.type, versionId: project.currentVersion.id, versionNo: project.currentVersion.number,
      title: project.name, entryPath: 'index.html', slideCount: 5, mediaPath: 'video.mp4',
    }
    snapshots.set(project.id, {
      run: {
        id: runId, projectId: project.id, status: 'SUCCEEDED', purpose: payload.instruction ? 'EDIT' : 'GENERATE',
        instruction: payload.instruction ?? null, baseVersionId: payload.baseVersionId ?? null,
        theme: null, slideCount: null, totalSteps: 1, createdAt: date,
        errorCode: null, errorMessage: null, startedAt: date, finishedAt: date,
      },
      events: [{ seq: 1, type: 'artifact', data: artifact }],
    })
    const response = { runId, status: 'SUCCEEDED' }
    receipts.set(key, response)
    if (outcome === 'network') {
      outcome = 'success'
      return call('Fetch.failRequest', { requestId, errorReason: 'ConnectionReset' })
    }
    return fulfill(requestId, 200, response)
  }
  if (request.method === 'GET' && path === `/api/projects/${project.id}`) return fulfill(requestId, 200, { project })
  // Preview/media endpoints also fail closed; fixture HTML is never mixed into production output.
  return fulfill(requestId, 404, error('FIXTURE_PREVIEW', 'Fixture preview omitted'))
}
socket.onmessage = ({ data }) => {
  const message = JSON.parse(data)
  if (message.method === 'Fetch.requestPaused') intercept(message.params).catch(failure => {
    if (!failure.message.includes('Invalid InterceptionId')) exceptions.push(failure.message)
  })
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
    if (await evaluate(`Boolean(${expression})`)) return
    await new Promise(resolve => setTimeout(resolve, 75))
  }
  throw new Error(`Timed out: ${expression}`)
}
async function input(text) {
  await evaluate(`(() => {
    const element = document.querySelector('[data-testid="generation-instruction"]');
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(element, ${JSON.stringify(text)});
    element.dispatchEvent(new Event('input', { bubbles: true }));
  })()`)
}
const composer = '[data-testid="project-action-composer"]'
const edit = '[data-testid="submit-edit"]'
const start = '[data-testid="start-generation"]'
async function click(selector) { await evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`) }
async function ready() { await waitFor(`document.querySelector('${start}') && !document.querySelector('${start}').disabled`) }
async function route(type, page = 'projects') {
  await evaluate(`location.hash = '${page}/${projects[type].id}'`)
  await waitFor(`document.querySelector('${composer}')`)
  await ready()
}
async function check(name, expression) {
  assert.equal(await evaluate(expression), true, name)
  checks.push(name)
}
try {
  await call('Runtime.enable')
  await call('Page.enable')
  await call('Fetch.enable', { patterns: [{ urlPattern: `${origin}/api/*`, requestStage: 'Request' }] })
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })
  await call('Page.navigate', { url: `${origin}/#projects/${projects.WEB.id}` })
  for (const type of Object.keys(projects)) {
    await route(type)
    await check(`${type} generation entry enabled`, `!document.querySelector('${start}').disabled`)
    if (type === 'VIDEO') {
      await check('server video settings and readiness shown', `document.querySelector('${composer}').textContent.includes('测试模型') && document.querySelector('${composer}').textContent.includes('模型尚未开通')`)
    }
    if (type === 'SLIDES') await check('slides honestly identifies local engine', `document.querySelector('${composer}').textContent.includes('本地排版引擎')`)
    await click(start)
    await waitFor(`location.hash === '#chat/${projects[type].id}' && document.querySelector('.chat-status.succeeded')`)
    await ready()
    assert.equal(requests.at(-1).projectId, projects[type].id)
  }
  assert.equal(requests.length, 4)
  assert.equal(requests[3].payload.model, 'fixture-video')
  checks.push('all four start existing projects; server video spec submitted')

  await route('GAME')
  const initialBase = projects.GAME.currentVersion.id
  await input('增加键盘控制和重新开始按钮')
  outcome = 'network'
  const before = requests.length
  await evaluate(`document.querySelector('${edit}').form.requestSubmit(); document.querySelector('${edit}').form.requestSubmit()`)
  await waitFor(`document.querySelector('.project-error')?.textContent.includes('无法连接')`)
  assert.equal(requests.length, before + 1)
  assert.equal(requests.at(-1).payload.baseVersionId, initialBase)
  assert.equal(requests.at(-1).payload.instruction, '增加键盘控制和重新开始按钮')
  await check('uncertain failure preserves instruction', `document.querySelector('[data-testid="generation-instruction"]').value === '增加键盘控制和重新开始按钮'`)
  await click(edit)
  await waitFor(`location.hash === '#chat/${projects.GAME.id}' && document.querySelector('.chat-status.succeeded')`)
  await ready()
  assert.equal(requests.at(-1).key, requests.at(-2).key)
  assert.deepEqual(requests.at(-1).payload, requests.at(-2).payload)
  checks.push('GAME edit body, UUID header, double submit guard and uncertain retry')
  await check('saved instruction and GAME artifact rendered', `document.querySelector('.chat-message.user').textContent.includes('增加键盘控制') && document.querySelector('.run-file').textContent.includes('游戏')`)
  await call('Page.reload')
  await waitFor(`document.querySelector('.chat-message.user')?.textContent.includes('增加键盘控制')`)
  await ready()
  checks.push('instruction and game result survive page reload')

  const followBase = projects.GAME.currentVersion.id
  await input('把分数放在右上角')
  outcome = 'provider'
  await click(edit)
  await waitFor(`document.querySelector('.project-error')?.textContent.includes('尚未开通')`)
  await check('provider rejection preserves follow-up input', `document.querySelector('[data-testid="generation-instruction"]').value === '把分数放在右上角'`)
  assert.equal(requests.at(-1).payload.baseVersionId, followBase)
  await click(edit)
  await waitFor(`document.querySelector('.chat-message.user')?.textContent.includes('把分数放在右上角')`)
  await ready()
  assert.equal(requests.at(-1).key, requests.at(-2).key)
  checks.push('follow-up binds refreshed successful version and retries failure')

  await input('保留这条修改')
  advance(projects.GAME)
  const staleCount = requests.length
  await click(edit)
  await waitFor(`document.querySelector('.project-error')?.textContent.includes('当前作品已更新')`)
  assert.equal(requests.length, staleCount, 'stale version blocked before POST')
  await evaluate(`Array.from(document.querySelectorAll('.project-error button')).find(button => button.textContent === '读取最新项目').click()`)
  await ready()
  await check('conflict refresh preserves input', `document.querySelector('[data-testid="generation-instruction"]').value === '保留这条修改'`)
  const refreshedBase = projects.GAME.currentVersion.id
  await click(edit)
  await waitFor(`document.querySelector('.chat-message.user')?.textContent.includes('保留这条修改')`)
  await ready()
  assert.equal(requests.at(-1).payload.baseVersionId, refreshedBase)
  checks.push('stale base requires explicit refresh before follow-up')

  await call('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
  for (const type of Object.keys(projects)) {
    await route(type)
    await check(`${type} phone has no horizontal overflow`, 'document.documentElement.scrollWidth <= innerWidth')
  }
  await route('GAME', 'chat')
  await check('follow-up chat phone layout', 'document.documentElement.scrollWidth <= innerWidth')

  const snapshot = snapshots.get(projects.GAME.id)
  snapshot.run.status = 'FAILED'
  snapshot.run.errorCode = 'PROVIDER_MODEL_NOT_ACTIVATED'
  snapshot.run.errorMessage = 'Fixture terminal failure'
  snapshot.events.push({
    seq: 2, type: 'status', data: { status: 'RUNNING', label: 'Lagging running event', code: null, message: null },
  })
  await call('Page.reload')
  await waitFor(`document.querySelector('.chat-status.failed')`)
  await check('terminal snapshot wins over lagging RUNNING event', `document.querySelector('.chat-agent-head').textContent.includes('Fixture terminal failure') && !document.querySelector('.chat-stop')`)
  snapshot.run.status = 'RUNNING'
  snapshot.run.errorCode = null
  snapshot.run.errorMessage = null
  await call('Page.reload')
  await waitFor(`document.querySelector('.chat-status.succeeded')`)
  assert.equal(streamCalls, 1)
  checks.push('SSE EOF re-reads terminal snapshot and restores completion')

  streamMode = 'active'
  snapshot.run.status = 'RUNNING'
  await call('Page.reload')
  await waitFor(`document.querySelector('[role="alert"]')?.textContent.includes('生成过程连接已中断')`)
  assert.equal(streamCalls, 4, 'three bounded stream attempts')
  checks.push('active EOF retries are bounded with explicit status recovery')
  assert.deepEqual(exceptions, [])
  console.log(`PASS: ${checks.length} generation browser checks (intercepted fixtures only).`)
  checks.forEach(name => console.log(`  ${name}`))
} finally {
  // Close the isolated tab while interception remains enabled, preventing late real API requests.
  await fetch(`${debuggerOrigin}/json/close/${target.id}`).catch(() => {})
  socket.close()
}
