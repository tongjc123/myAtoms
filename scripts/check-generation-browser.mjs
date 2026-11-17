// Real local UI/API smoke. Creates its own anonymous projects and only generates local slides.
// WEB/GAME/VIDEO entries are inspected without making paid requests.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const origin = 'http://127.0.0.1:5173'
const debug = 'http://127.0.0.1:9228'
const evidence = resolve('.trellis/tasks/11-16-s1-project-generation/evidence')
await mkdir(evidence, { recursive: true })
const browser = spawn('C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', [
  '--headless=new', '--no-first-run', '--no-default-browser-check',
  '--remote-debugging-port=9228', `--user-data-dir=${resolve('.cache/browser-generation')}`, 'about:blank',
], { windowsHide: true, stdio: 'ignore' })
const delay = (ms) => new Promise((done) => setTimeout(done, ms))
let version
for (let i = 0; i < 50; i++) {
  try { version = await (await fetch(`${debug}/json/version`)).json(); break } catch { await delay(100) }
}
assert.ok(version, 'Dedicated test Chrome must start')
const socket = new WebSocket(version.webSocketDebuggerUrl)
await new Promise((done, fail) => { socket.onopen = done; socket.onerror = fail })
let sequence = 0
let sessionId
const pending = new Map()
const errors = []
const projects = []
const checks = []
socket.onmessage = ({ data }) => {
  const message = JSON.parse(data)
  if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails.text)
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
async function screenshot(name) {
  await evaluate('window.scrollTo(0,0)')
  const shot = await call('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
  await writeFile(resolve(evidence, name), Buffer.from(shot.data, 'base64'))
}
try {
  const { browserContextId } = await call('Target.createBrowserContext', {}, false)
  const { targetId } = await call('Target.createTarget', { url: 'about:blank', browserContextId }, false)
  ;({ sessionId } = await call('Target.attachToTarget', { targetId, flatten: true }, false))
  await call('Page.enable')
  await call('Runtime.enable')
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false })
  await call('Page.navigate', { url: origin })
  await wait('document.readyState === "complete" && Boolean(document.querySelector("main"))')
  const template = await request('/api/templates/star-swarm/clone', 'POST', { name: '游戏修改入口验收' })
  assert.equal(template.status, 201, JSON.stringify(template.body))
  projects.push(template.body.project.id)
  const byType = { GAME: template.body.project.id }
  for (const type of ['WEB', 'SLIDES', 'VIDEO']) {
    const created = await request('/api/projects', 'POST', {
      name: `${type} 入口验收`, type, mode: 'BUILD',
      prompt: '团队季度回顾。第一季度完成产品原型。第二季度开展用户测试。第三季度优化体验。',
    })
    assert.equal(created.status, 201)
    projects.push(created.body.project.id)
    byType[type] = created.body.project.id
  }
  for (const type of ['WEB', 'GAME', 'VIDEO', 'SLIDES']) {
    await evaluate(`location.hash = "projects/${byType[type]}"`)
    const expectedName = type === 'GAME' ? '游戏修改入口验收' : `${type} 入口验收`
    await wait(`document.querySelector(".project-detail h1")?.textContent === ${JSON.stringify(expectedName)}`)
    await wait('document.querySelector("[data-testid=start-generation]")?.disabled === false')
    const result = await evaluate(`({
      text:document.querySelector(".project-detail").innerText,
      buttons:[...document.querySelectorAll(".project-detail button")].filter(b=>!b.disabled).map(b=>b.textContent.trim())
    })`)
    assert.ok(!result.text.includes('尚未开放'), type)
    assert.ok(result.buttons.some(text => text.includes('生成')), `${type}: ${result.buttons.join(',')}`)
    check(`${type} project has an enabled real generation entry`)
    if (type === 'GAME') {
      assert.ok(await evaluate('Boolean(document.querySelector(".project-detail textarea"))'))
      check('cloned GAME exposes an instruction edit composer')
      await screenshot('game-project-desktop.png')
    }
    if (type === 'VIDEO') {
      assert.ok(/720p|720P|模型|画幅/.test(result.text))
      check('VIDEO project exposes model/specification controls')
    }
  }
  await evaluate('document.querySelector("[data-testid=start-generation]").click()')
  await wait('location.hash.startsWith("#chat/")')
  let snapshot
  for (let i = 0; i < 100; i++) {
    const response = await request(`/api/projects/${byType.SLIDES}/runs/latest`)
    snapshot = response.body
    if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(snapshot.run?.status)) break
    await delay(150)
  }
  assert.equal(snapshot.run.status, 'SUCCEEDED', JSON.stringify(snapshot))
  assert.ok(snapshot.events.some(event => event.type === 'artifact'))
  check('SLIDES project entry executes local generation and persists an artifact')
  await call('Page.reload')
  await wait('Boolean(document.querySelector(".run-result"))')
  check('generated slides survive browser refresh')
  await screenshot('slides-project-result.png')
  await evaluate(`location.hash = "projects/${byType.GAME}"`)
  await wait('Boolean(document.querySelector(".project-detail textarea"))')
  await call('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
  assert.equal(await evaluate('document.documentElement.scrollWidth <= innerWidth'), true)
  check('mobile project edit controls have no horizontal overflow')
  await screenshot('game-project-phone.png')
  assert.deepEqual(errors, [])
  check('no uncaught browser exceptions')
  await writeFile(resolve(evidence, 'real-browser-results.json'), JSON.stringify({
    at: new Date().toISOString(), mode: 'real API/MySQL; only local slides generation; no paid submissions',
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
