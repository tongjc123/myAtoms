// Real UI/API/MySQL verification. Only local slide generation; no paid model calls.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const origin = 'http://127.0.0.1:5173'
const debug = 'http://127.0.0.1:9230'
const activeTask = '.trellis/tasks/11-16-s1-version-history'
const taskDirectory = existsSync(resolve(activeTask, 'task.json'))
  ? activeTask : '.trellis/tasks/archive/2026-11/11-16-s1-version-history'
const evidence = resolve(taskDirectory, 'evidence')
const downloads = resolve('.cache/history-downloads')
await mkdir(evidence, { recursive: true })
await mkdir(downloads, { recursive: true })
const browser = spawn('C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', [
  '--headless=new', '--no-first-run', '--no-default-browser-check',
  '--remote-debugging-port=9230', `--user-data-dir=${resolve('.cache/browser-history')}`, 'about:blank',
], { windowsHide: true, stdio: 'ignore' })
const delay = (ms) => new Promise((done) => setTimeout(done, ms))
let connection
for (let i = 0; i < 50; i++) {
  try { connection = await (await fetch(`${debug}/json/version`)).json(); break } catch { await delay(100) }
}
assert.ok(connection, 'Dedicated test Chrome starts')
const socket = new WebSocket(connection.webSocketDebuggerUrl)
await new Promise((done, fail) => { socket.onopen = done; socket.onerror = fail })
let sequence = 0
let sessionId
const pending = new Map()
const errors = []
const projects = []
const checks = []
const finishedDownloads = []
socket.onmessage = ({ data }) => {
  const message = JSON.parse(data)
  if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails.text)
  if (message.method === 'Browser.downloadProgress' && message.params.state === 'completed') {
    finishedDownloads.push(message.params.guid)
  }
  const request = pending.get(message.id)
  if (!request) return
  clearTimeout(request.timer)
  pending.delete(message.id)
  if (message.error) request.reject(new Error(JSON.stringify(message.error)))
  else request.resolve(message.result)
}
function call(method, params = {}, attached = true) {
  return new Promise((resolve, reject) => {
    const id = ++sequence
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`Timeout ${method}`)) }, 15000)
    pending.set(id, { resolve, reject, timer })
    socket.send(JSON.stringify({ id, method, params, ...(attached && sessionId ? { sessionId } : {}) }))
  })
}
async function evaluate(expression) {
  const result = await call('Runtime.evaluate', {
    expression: `(async () => (${expression}))()`, awaitPromise: true, returnByValue: true,
  })
  if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails))
  return result.result.value
}
async function wait(expression) {
  for (let i = 0; i < 150; i++) {
    assert.deepEqual(errors, [])
    if (await evaluate(expression)) return
    await delay(100)
  }
  throw new Error(`Not ready: ${expression}`)
}
async function request(path, method = 'GET', body) {
  return evaluate(`(async () => {
    const session = await (await fetch('/api/session', {method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})).json();
    const response = await fetch(${JSON.stringify(path)}, {
      method:${JSON.stringify(method)},
      headers:{'Content-Type':'application/json','X-CSRF-Token':session.csrfToken,'Idempotency-Key':crypto.randomUUID()},
      ${body === undefined ? '' : `body:JSON.stringify(${JSON.stringify(body)}),`}
    });
    return {status:response.status,body:response.status === 204 ? null : await response.json()};
  })()`)
}
function check(name) { checks.push(name); console.log(`PASS ${name}`) }
function row(id) { return `[data-testid="history-row"][data-version-id="${id}"]` }
async function click(selector) {
  await wait(`document.querySelector(${JSON.stringify(selector)})?.disabled === false`)
  await evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`)
}
async function screenshot(name) {
  await evaluate('document.querySelector("[data-testid=history-panel]")?.scrollIntoView({block:"start"})')
  const shot = await call('Page.captureScreenshot', { format: 'png' })
  await writeFile(resolve(evidence, name), Buffer.from(shot.data, 'base64'))
}
async function openProject(project) {
  await evaluate(`location.hash = "projects/${project.id}"`)
  await wait(`document.querySelector(".project-detail h1")?.textContent === ${JSON.stringify(project.name)}`)
  await wait('Boolean(document.querySelector("[data-testid=history-panel]"))')
}
try {
  const { browserContextId } = await call('Target.createBrowserContext', {}, false)
  const { targetId } = await call('Target.createTarget', { url: 'about:blank', browserContextId }, false)
  ;({ sessionId } = await call('Target.attachToTarget', { targetId, flatten: true }, false))
  await call('Browser.setDownloadBehavior', {
    behavior: 'allowAndName', browserContextId, downloadPath: downloads, eventsEnabled: true,
  }, false)
  await call('Page.enable')
  await call('Runtime.enable')
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false })
  await call('Page.navigate', { url: origin })
  await wait('document.readyState === "complete" && Boolean(document.querySelector("main"))')
  const templates = await request('/api/templates?type=SLIDES')
  const slideTemplate = templates.body.items[0]
  assert.ok(slideTemplate)
  const cloned = await request(`/api/templates/${slideTemplate.slug}/clone`, 'POST', { name: '历史版本恢复验收' })
  assert.equal(cloned.status, 201)
  const slides = cloned.body.project
  projects.push(slides.id)
  const firstVersion = slides.currentVersion.id
  const originalPreview = await request(`/api/projects/${slides.id}/preview-sessions`, 'POST', { versionId: firstVersion })
  assert.equal(originalPreview.status, 200)
  const oldHtml = await evaluate(`fetch(${JSON.stringify(originalPreview.body.baseUrl + originalPreview.body.entryPath)}).then(r=>r.text())`)
  const started = await request(`/api/projects/${slides.id}/runs`, 'POST', {})
  assert.equal(started.status, 202)
  let snapshot
  for (let i = 0; i < 150; i++) {
    snapshot = (await request(`/api/projects/${slides.id}/runs/latest`)).body
    if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(snapshot.run?.status)) break
    await delay(100)
  }
  assert.equal(snapshot.run.status, 'SUCCEEDED', JSON.stringify(snapshot))
  const before = (await request(`/api/projects/${slides.id}`)).body.project
  assert.equal(before.currentVersion.number, 2)
  await openProject(slides)
  await wait(`document.querySelectorAll("[data-testid=history-row]").length === 2`)
  check('two persisted versions appear in the real project history')
  await click(`${row(firstVersion)} [data-testid=history-preview]`)
  await wait('document.querySelector("[data-testid=history-panel] iframe")?.src.includes("/api/previews/")')
  const historyPreview = await evaluate('document.querySelector("[data-testid=history-panel] iframe").src')
  const selectedHtml = await evaluate(`fetch(${JSON.stringify(historyPreview)}).then(r=>r.text())`)
  assert.equal(selectedHtml, oldHtml)
  assert.equal((await request(`/api/projects/${slides.id}`)).body.project.currentVersion.id, before.currentVersion.id)
  check('historical preview reads exact old content without moving the current version')
  await click(`${row(firstVersion)} [data-testid=history-restore]`)
  await wait('Boolean(document.querySelector("[data-testid=history-confirm-restore]"))')
  assert.equal((await request(`/api/projects/${slides.id}`)).body.project.currentVersion.id, before.currentVersion.id)
  check('restore waits for explicit confirmation')
  await click('[data-testid=history-confirm-restore]')
  await wait('document.querySelectorAll("[data-testid=history-row]").length === 3')
  const restored = (await request(`/api/projects/${slides.id}`)).body.project
  assert.equal(restored.currentVersion.number, 3)
  assert.equal(restored.currentVersion.kind, 'RESTORE')
  const history = (await request(`/api/projects/${slides.id}/versions`)).body
  assert.equal(history.items[0].parentVersionId, before.currentVersion.id)
  assert.equal(history.items[0].restoredFromVersionId, firstVersion)
  const restoredPreview = await request(`/api/projects/${slides.id}/preview-sessions`, 'POST', { versionId: restored.currentVersion.id })
  const restoredHtml = await evaluate(`fetch(${JSON.stringify(restoredPreview.body.baseUrl + restoredPreview.body.entryPath)}).then(r=>r.text())`)
  assert.equal(restoredHtml, oldHtml)
  check('confirmed restore creates v3 with correct provenance and byte-identical content')
  const stale = await request(`/api/projects/${slides.id}/versions/${firstVersion}/restore`, 'POST', {
    expectedCurrentVersionId: before.currentVersion.id,
  })
  assert.equal(stale.status, 409)
  assert.equal(stale.body.code, 'VERSION_CONFLICT')
  check('a stale second page cannot overwrite the restored version')
  await call('Page.reload')
  await wait('document.querySelectorAll("[data-testid=history-row]").length === 3')
  check('restored history survives refresh')
  await screenshot('history-desktop.png')
  await call('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
  assert.equal(await evaluate('document.documentElement.scrollWidth <= innerWidth'), true)
  await screenshot('history-phone.png')
  check('history controls fit phone width')

  for (const type of ['WEB', 'GAME']) {
    const catalog = await request(`/api/templates?type=${type}`)
    const copied = await request(`/api/templates/${catalog.body.items[0].slug}/clone`, 'POST', { name: `${type}源码导出验收` })
    assert.equal(copied.status, 201)
    const project = copied.body.project
    projects.push(project.id)
    await openProject(project)
    const initialCount = finishedDownloads.length
    await click(`${row(project.currentVersion.id)} [data-testid=history-export]`)
    for (let i = 0; i < 100 && finishedDownloads.length === initialCount; i++) await delay(100)
    assert.equal(finishedDownloads.length, initialCount + 1)
    const zip = await readFile(resolve(downloads, finishedDownloads.at(-1)))
    assert.equal(zip.readUInt32LE(0), 0x04034b50)
    assert.ok(zip.length > 100)
    check(`${type} source ZIP downloads through the real UI`)
  }
  const draft = await request('/api/projects', 'POST', {
    name: '无版本空态验收', type: 'WEB', mode: 'BUILD', prompt: '空态检查，不发起模型调用。',
  })
  assert.equal(draft.status, 201)
  projects.push(draft.body.project.id)
  await openProject(draft.body.project)
  await wait('document.querySelector("[data-testid=history-panel]")?.innerText.includes("暂无") || document.querySelector("[data-testid=history-panel]")?.innerText.includes("还没有")')
  check('a draft shows an honest empty history')
  assert.deepEqual(errors, [])
  check('no uncaught browser exceptions')
  await writeFile(resolve(evidence, 'real-browser-results.json'), JSON.stringify({
    at: new Date().toISOString(), mode: 'real UI/API/MySQL; local slides only; no paid calls',
    passed: checks.length, checks,
  }, null, 2) + '\n')
} finally {
  for (const id of projects) {
    const current = await request(`/api/projects/${id}`).catch(() => null)
    if (current?.status === 200) {
      await request(`/api/projects/${id}`, 'DELETE', { expectedRevision: current.body.project.revision }).catch(() => null)
    }
  }
  await call('Browser.close', {}, false).catch(() => {})
  socket.close()
  browser.unref()
}
