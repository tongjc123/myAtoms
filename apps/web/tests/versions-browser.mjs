// All API traffic is intercepted. Use an isolated Chromium on 9229; no provider calls.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'

const origin = process.env.WEB_TEST_ORIGIN ?? 'http://127.0.0.1:5173'
const debuggerOrigin = process.env.WEB_TEST_DEBUGGER ?? 'http://127.0.0.1:9229'
const target = await (await fetch(`${debuggerOrigin}/json/new?about:blank`, { method: 'PUT' })).json()
const socket = new WebSocket(target.webSocketDebuggerUrl)
await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject })
let sequence = 0
const pending = new Map()
const exceptions = []
const checks = []
const requests = []
const receipts = new Map()
const previews = []
const exports = []
const date = '2026-11-16T00:00:00Z'
const versions = Array.from({ length: 12 }, (_, index) => ({
  id: randomUUID(), number: 12 - index, kind: index ? 'TEMPLATE' : 'EDIT',
  summary: `测试文件快照 ${12 - index}`, createdAt: date, parentVersionId: null,
  restoredFromVersionId: null, entryPath: 'index.html',
}))
const project = {
  id: randomUUID(), type: 'WEB', name: '版本历史 fixture', prompt: '测试需求',
  mode: 'BUILD', status: 'READY', revision: 1, createdAt: date, updatedAt: date,
  currentVersion: versions[0], sourceTemplate: null,
}
let historyMode = 'ready'
let restoreMode = 'success'
let historyReads = 0
function call(method, params = {}) {
  const id = ++sequence
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)) }, 10000)
    pending.set(id, {
      resolve: result => { clearTimeout(timer); resolve(result) },
      reject: error => { clearTimeout(timer); reject(error) },
    })
    socket.send(JSON.stringify({ id, method, params }))
  })
}
function failure(code, message = code) { return { code, message, retryable: true, requestId: randomUUID() } }
function fulfill(requestId, responseCode, body) {
  return call('Fetch.fulfillRequest', { requestId, responseCode,
    responseHeaders: [{ name: 'Content-Type', value: 'application/json' }],
    body: Buffer.from(JSON.stringify(body)).toString('base64') })
}
function advance(source = versions[0]) {
  const next = { ...source, id: randomUUID(), number: versions[0].number + 1,
    kind: 'RESTORE', parentVersionId: project.currentVersion.id, restoredFromVersionId: source.id }
  versions.unshift(next)
  // Deliberately preserve project.revision: version changes do not rename the project.
  project.currentVersion = next
}
async function intercept({ requestId, request }) {
  const url = new URL(request.url)
  const path = url.pathname
  if (path === '/api/system/capabilities') return fulfill(requestId, 200, {
    generationAvailable: true, projectManagementAvailable: true, reasonCode: 'READY', message: 'Fixture',
    projectTypes: ['WEB', 'GAME', 'SLIDES', 'VIDEO'],
  })
  if (path === '/api/session') return fulfill(requestId, 200, {
    sessionId: randomUUID(), csrfToken: 'fixture-csrf', expiresAt: '2099-01-01T00:00:00Z',
  })
  if (path.endsWith('/runs/latest')) return fulfill(requestId, 404, failure('RUN_NOT_FOUND'))
  if (path === '/api/system/provider') return fulfill(requestId, 503, failure('NOT_ACTIVATED'))
  if (path === `/api/projects/${project.id}`) return fulfill(requestId, 200, { project })
  if (path.endsWith('/versions')) {
    historyReads++
    const page = Number(url.searchParams.get('page'))
    if (historyMode === 'error') return fulfill(requestId, 503, failure('FIXTURE_HISTORY_ERROR', '历史读取失败'))
    if (historyMode === 'loading') await new Promise(resolve => setTimeout(resolve, 700))
    return fulfill(requestId, 200, {
      page, pageSize: 10, total: historyMode === 'empty' ? 0 : versions.length,
      currentVersionId: historyMode === 'empty' ? null : project.currentVersion.id,
      items: historyMode === 'empty' ? [] : versions.slice(page * 10, page * 10 + 10),
    })
  }
  if (path.endsWith('/preview-sessions')) {
    const body = JSON.parse(request.postData)
    previews.push(body.versionId)
    assert.ok(versions.some(version => version.id === body.versionId), 'preview binds exact known version')
    return fulfill(requestId, 200, { baseUrl: `/api/previews/${body.versionId}/`,
      entryPath: project.type === 'VIDEO' ? 'video.mp4' : 'index.html', expiresAt: '2099-01-01T00:00:00Z' })
  }
  if (path.startsWith('/api/previews/')) return call('Fetch.fulfillRequest', {
    requestId, responseCode: 200, responseHeaders: [{ name: 'Content-Type', value: 'text/html' }],
    body: Buffer.from('<!doctype html><p>版本预览 fixture</p>').toString('base64'),
  })
  if (path.endsWith('/source.zip')) {
    exports.push(path.split('/')[5])
    return call('Fetch.fulfillRequest', { requestId, responseCode: 200, responseHeaders: [
      { name: 'Content-Type', value: 'application/zip' },
      { name: 'Content-Disposition', value: 'attachment; filename="fixture-source.zip"' },
    ], body: Buffer.from('504b0506000000000000000000000000000000000000', 'hex').toString('base64') })
  }
  if (path.endsWith('/restore')) {
    const headers = Object.fromEntries(Object.entries(request.headers).map(([key, value]) => [key.toLowerCase(), value]))
    const body = JSON.parse(request.postData)
    const key = headers['idempotency-key']
    const versionId = path.split('/')[5]
    requests.push({ body, key, versionId })
    assert.match(key, /^[\da-f-]{36}$/i)
    assert.equal(headers['x-csrf-token'], 'fixture-csrf')
    if (receipts.has(key)) return fulfill(requestId, 201, receipts.get(key))
    if (restoreMode === 'busy') return fulfill(requestId, 409, failure('PROJECT_BUSY', '项目正在生成，暂时无法恢复'))
    if (body.expectedCurrentVersionId !== project.currentVersion.id) return fulfill(requestId, 409, failure('VERSION_CONFLICT', '当前版本已更新'))
    advance(versions.find(version => version.id === versionId))
    receipts.set(key, structuredClone({ project }))
    if (restoreMode === 'network') {
      restoreMode = 'success'
      return call('Fetch.failRequest', { requestId, errorReason: 'ConnectionReset' })
    }
    return fulfill(requestId, 201, { project })
  }
  return fulfill(requestId, 404, failure('FIXTURE_UNCONFIGURED'))
}
socket.onmessage = ({ data }) => {
  const message = JSON.parse(data)
  if (message.method === 'Fetch.requestPaused') intercept(message.params).catch(error => {
    if (!error.message.includes('Invalid InterceptionId')) exceptions.push(error.message)
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
  for (let attempt = 0; attempt < 100; attempt++) {
    if (exceptions.length) throw new Error(exceptions.join('\n'))
    if (await evaluate(`Boolean(${expression})`)) return
    await new Promise(resolve => setTimeout(resolve, 75))
  }
  throw new Error(`Timed out: ${expression}`)
}
const sel = name => `[data-testid="history-${name}"]`
const row = id => `${sel('row')}[data-version-id="${id}"]`
async function click(selector) { await evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`) }
async function ready() { await waitFor(`document.querySelectorAll('${sel('row')}').length === 10`) }
async function reload() { await call('Page.reload'); await ready() }
async function check(name, expression) {
  assert.equal(await evaluate(expression), true, name)
  checks.push(name)
}
try {
  await call('Runtime.enable')
  await call('Page.enable')
  await call('Browser.setDownloadBehavior', { behavior: 'deny' })
  await call('Fetch.enable', { patterns: [{ urlPattern: `${origin}/api/*`, requestStage: 'Request' }] })
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })
  historyMode = 'loading'
  await call('Page.navigate', { url: `${origin}/#projects/${project.id}` })
  await waitFor(`document.querySelector('${sel('panel')}')?.textContent.includes('正在读取版本历史')`)
  await ready()
  historyMode = 'ready'
  checks.push('loading state and first history page')
  await check('current version marker and disabled self restore',
    `document.querySelector('${row(versions[0].id)} ${sel('current')}') !== null && document.querySelector('${row(versions[0].id)} ${sel('restore')}').disabled`)
  const old = versions[1]
  const originalCurrent = project.currentVersion.id
  await click(`${row(old.id)} ${sel('preview')}`)
  await waitFor(`document.querySelector('${sel('selected')} iframe')`)
  assert.equal(previews.at(-1), old.id)
  assert.equal(project.currentVersion.id, originalCurrent)
  checks.push('historical preview binds immutable ID without changing current version')
  await click(sel('next'))
  await waitFor(`document.querySelectorAll('${sel('row')}').length === 2`)
  await check('selection persists across pages', `document.querySelector('${sel('selected')}').dataset.versionId === '${old.id}'`)
  await click(sel('previous'))
  await ready()
  await click(`${row(old.id)} ${sel('export')}`)
  await waitFor(`document.querySelector('${sel('panel')}').textContent.includes('已交给浏览器下载')`)
  assert.equal(exports.at(-1), old.id)
  checks.push('source download requests exact selected version ZIP')
  await click(`${row(old.id)} ${sel('restore')}`)
  await waitFor(`document.querySelector('dialog[open]')`)
  await check('modal focuses cancel', `document.activeElement.textContent === '取消'`)
  await call('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 })
  await call('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 })
  await waitFor(`!document.querySelector('dialog[open]')`)
  await check('Escape restores focus', `document.activeElement.dataset.testid === 'history-restore'`)
  assert.equal(requests.length, 0)
  checks.push('confirmation cancellation sends no mutation')
  await click(`${row(old.id)} ${sel('restore')}`)
  restoreMode = 'network'
  await evaluate(`document.querySelector('${sel('confirm-restore')}').click(); document.querySelector('${sel('confirm-restore')}').click()`)
  await waitFor(`document.querySelector('dialog .project-error')?.textContent.includes('无法连接')`)
  assert.equal(requests.length, 1)
  assert.equal(requests[0].body.expectedCurrentVersionId, originalCurrent)
  await click(sel('confirm-restore'))
  await waitFor(`!document.querySelector('dialog[open]')`)
  await ready()
  assert.equal(requests.length, 2)
  assert.deepEqual(requests[0], requests[1])
  assert.equal(versions.length, 13)
  checks.push('double submit blocked; uncertain retry preserves UUID, target and base; one restore')
  await check('selected old preview remains after restore', `document.querySelector('${sel('selected')}').dataset.versionId === '${old.id}'`)

  await click(`${row(old.id)} ${sel('restore')}`)
  advance()
  await click(sel('confirm-restore'))
  await waitFor(`document.querySelector('${sel('refresh-conflict')}')`)
  const conflictCount = requests.length
  await check('stale base blocks automatic retry', `document.querySelector('${sel('confirm-restore')}').disabled`)
  await click(sel('refresh-conflict'))
  await ready()
  assert.equal(requests.length, conflictCount)
  await click(`${row(old.id)} ${sel('restore')}`)
  await click(sel('confirm-restore'))
  await waitFor(`!document.querySelector('dialog[open]')`)
  await ready()
  assert.notEqual(requests.at(-1).key, requests.at(-2).key)
  checks.push('conflict refresh requires fresh explicit confirmation and fresh base')

  restoreMode = 'busy'
  await click(`${row(old.id)} ${sel('restore')}`)
  await click(sel('confirm-restore'))
  await waitFor(`document.querySelector('dialog')?.textContent.includes('项目正在生成')`)
  await click(sel('refresh-conflict'))
  await ready()
  restoreMode = 'success'
  checks.push('busy conflict explicitly surfaced without mutation replay')

  const readsBefore = historyReads
  advance()
  await evaluate(`Array.from(document.querySelectorAll('.project-detail > .section-heading button')).find(button => button.textContent === '刷新项目').click()`)
  await waitFor(`document.querySelector('${row(project.currentVersion.id)} ${sel('current')}')`)
  assert.ok(historyReads > readsBefore)
  checks.push('parent refresh updates history when currentVersion changes without revision change')
  await reload()
  await check('restore history survives refresh', `document.querySelector('${sel('panel')}').textContent.includes('恢复来源：v')`)
  for (const type of ['GAME', 'SLIDES', 'VIDEO']) {
    project.type = type
    await reload()
    await check(`${type} export eligibility`, type === 'GAME'
      ? `document.querySelector('${sel('export')}') !== null`
      : `document.querySelector('${sel('export')}') === null`)
    if (type === 'VIDEO') {
      await click(`${row(old.id)} ${sel('preview')}`)
      await waitFor(`document.querySelector('${sel('selected')} video')`)
      assert.equal(previews.at(-1), old.id)
      checks.push('historical VIDEO uses native video and exact version')
    }
  }
  await call('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
  await check('mobile history has no horizontal overflow', 'document.documentElement.scrollWidth <= innerWidth')
  await click(`${row(old.id)} ${sel('restore')}`)
  await check('mobile confirmation fits viewport', `document.querySelector('dialog').getBoundingClientRect().right <= innerWidth`)
  await evaluate(`document.querySelector('dialog button').click()`)
  historyMode = 'empty'
  await call('Page.reload')
  await waitFor(`document.querySelector('${sel('panel')}')?.textContent.includes('暂无版本记录')`)
  historyMode = 'error'
  await call('Page.reload')
  await waitFor(`document.querySelector('${sel('panel')} .project-error')`)
  historyMode = 'ready'
  await click(`${sel('panel')} .project-error button`)
  await ready()
  checks.push('empty/error states and explicit read retry')
  assert.deepEqual(exceptions, [])
  console.log(`PASS: ${checks.length} version history browser checks (fixtures only).`)
  checks.forEach(name => console.log(`  ${name}`))
} finally {
  await fetch(`${debuggerOrigin}/json/close/${target.id}`).catch(() => {})
  socket.close()
}
