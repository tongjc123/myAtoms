// Real browser + API + MySQL acceptance, with two isolated cookie jars.
// Run "prepare", restart the API, then run "finish" against the same Chrome.
// Never intercept API requests or write credentials to evidence.
import assert from 'node:assert/strict'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const phase = process.argv[2]
assert.ok(['prepare', 'finish'].includes(phase), 'Use prepare or finish')
const origin = process.env.WEB_TEST_ORIGIN ?? 'http://127.0.0.1:5173'
const debuggerOrigin = process.env.WEB_TEST_DEBUGGER ?? 'http://127.0.0.1:9224'
const evidence = resolve(process.env.WEB_TEST_EVIDENCE ?? '.trellis/tasks/11-12-s1-anonymous-projects/evidence')
const stateFile = resolve('.cache/projects-browser-state.json')
await mkdir(evidence, { recursive: true })
const version = await (await fetch(`${debuggerOrigin}/json/version`)).json()
const socket = new WebSocket(version.webSocketDebuggerUrl)
await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject })
let sequence = 0
const pending = new Map()
const exceptions = []
socket.onmessage = ({ data }) => {
  const message = JSON.parse(data)
  if (message.method === 'Runtime.exceptionThrown') exceptions.push(message.params.exceptionDetails.text)
  const entry = pending.get(message.id)
  if (entry) {
    pending.delete(message.id)
    clearTimeout(entry.timer)
    if (message.error) entry.reject(new Error(JSON.stringify(message.error)))
    else entry.resolve(message.result)
  }
}
function call(method, params = {}, sessionId) {
  return new Promise((resolve, reject) => {
    const id = ++sequence
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`Timeout: ${method}`)) }, 15000)
    pending.set(id, { resolve, reject, timer })
    socket.send(JSON.stringify({ id, method, params, sessionId }))
  })
}
async function attach(targetId) {
  const { sessionId } = await call('Target.attachToTarget', { targetId, flatten: true })
  const command = (method, params) => call(method, params, sessionId)
  await command('Page.enable')
  await command('Runtime.enable')
  const evaluate = async (expression) => {
    const result = await command('Runtime.evaluate', {
      expression: `(async () => (${expression}))()`, returnByValue: true, awaitPromise: true,
    })
    if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails))
    return result.result.value
  }
  const wait = async (expression) => {
    for (let attempt = 0; attempt < 150; attempt++) {
      assert.deepEqual(exceptions, [])
      if (await evaluate(expression)) return
      await new Promise((resolve) => setTimeout(resolve, 100))
    }
    throw new Error(`Not ready: ${expression}`)
  }
  return {
    command, evaluate, wait,
    input: async (selector, value, tag = 'HTMLInputElement') => evaluate(`(() => {
      const el = document.querySelector(${JSON.stringify(selector)});
      Object.getOwnPropertyDescriptor(${tag}.prototype, 'value').set.call(el, ${JSON.stringify(value)});
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    })()`),
    click: async (text) => evaluate(`Array.from(document.querySelectorAll('button')).find(b => b.textContent === ${JSON.stringify(text)}).click()`),
    screenshot: async (name) => {
      const { data } = await command('Page.captureScreenshot', { format: 'png' })
      await writeFile(resolve(evidence, name), Buffer.from(data, 'base64'))
    },
  }
}
async function createContext() {
  const { browserContextId } = await call('Target.createBrowserContext')
  const { targetId } = await call('Target.createTarget', { url: 'about:blank', browserContextId })
  return { browserContextId, targetId }
}
async function createDraft(tab, label, name) {
  await tab.evaluate('location.hash = "home"')
  await tab.wait('Boolean(document.querySelector("#project-name"))')
  await tab.click(label)
  await tab.input('#project-name', name)
  await tab.input('#prompt', '真实数据库验收：保存需求，不调用模型。', 'HTMLTextAreaElement')
  await tab.input('#project-mode', 'PLAN', 'HTMLSelectElement')
  await tab.wait('!document.querySelector(".save-draft").disabled')
  await tab.click('保存项目草稿')
  await tab.wait(`document.querySelector(".project-detail h1")?.textContent === ${JSON.stringify(name)}`)
  assert.ok(await tab.evaluate('document.querySelector(".project-facts").textContent.includes("规划")'))
  return tab.evaluate('location.hash.split("/")[1]')
}
async function deleteCurrent(tab) {
  await tab.click('删除项目…')
  await tab.click('确认删除草稿')
  await tab.wait('location.hash === "#projects" && !document.querySelector(".project-detail")')
}
let state
try {
  if (phase === 'prepare') {
    state = { a: await createContext(), b: await createContext(), checks: [], startedAt: new Date().toISOString() }
  } else {
    state = JSON.parse(await readFile(stateFile, 'utf8'))
  }
  const a = await attach(state.a.targetId)
  const b = await attach(state.b.targetId)
  const check = (name) => { state.checks.push(name); console.log(`PASS ${name}`) }
  await a.command('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })
  if (phase === 'prepare') {
    for (const tab of [a, b]) {
      await tab.command('Page.navigate', { url: `${origin}/#home` })
      await tab.wait('document.querySelector("#service-message")?.textContent.includes("草稿可以保存")')
    }
    check('independent anonymous visitors connect to real API')
    assert.equal(await a.evaluate('document.querySelector(".save-draft").disabled'), true)
    check('empty creation is disabled')
    state.projectId = await createDraft(a, '网页', 'MySQL 持久化验收')
    check('web draft saved through UI with actual PLAN mode')
    assert.equal(await a.evaluate('document.querySelector("[aria-describedby=generation-status]").disabled'), true)
    check('draft does not imply generation')
    await a.command('Page.reload')
    await a.wait('document.querySelector(".project-detail h1")?.textContent === "MySQL 持久化验收"')
    check('page reload restores saved project and requirement')
    await a.input('#rename-project', 'MySQL 重启后仍保留')
    await a.click('保存名称')
    await a.wait('document.querySelector(".project-detail h1")?.textContent === "MySQL 重启后仍保留"')
    check('rename saved through UI')
    await a.click('删除项目…')
    assert.equal(await a.evaluate('document.activeElement.textContent'), '取消')
    await a.click('取消')
    assert.equal(await a.evaluate('Boolean(document.querySelector(".delete-confirmation"))'), false)
    check('delete confirmation can be cancelled')
    await a.screenshot('projects-desktop.png')
    await b.evaluate('location.hash = "projects"')
    await b.wait('Boolean(document.querySelector(".empty-projects"))')
    assert.equal(await b.evaluate('(await (await fetch("/api/projects")).json()).total'), 0)
    check('second visitor sees no first-visitor projects')
    const foreignStatuses = await b.evaluate(`(async () => {
      const session = await (await fetch('/api/session', { method:'POST', headers:{'Content-Type':'application/json'}, body:'{}' })).json();
      const path = '/api/projects/${state.projectId}';
      const statuses = [(await fetch(path)).status];
      for (const method of ['PATCH','DELETE']) statuses.push((await fetch(path, {
        method, headers:{'Content-Type':'application/json','X-CSRF-Token':session.csrfToken,'Idempotency-Key':crypto.randomUUID()},
        body:JSON.stringify(method === 'PATCH' ? {name:'越权尝试',expectedRevision:2} : {expectedRevision:2})
      })).status);
      return statuses;
    })()`)
    assert.deepEqual(foreignStatuses, [404, 404, 404])
    check('second visitor cannot read rename or delete a known foreign UUID')
    await b.evaluate(`location.hash = "projects/${state.projectId}"`)
    await b.wait('document.querySelector(".project-error")?.textContent.includes("项目不存在")')
    check('foreign project UI shows explicit error')
    for (const label of ['游戏', '幻灯片', '视频']) {
      await createDraft(a, label, `${label}草稿验收`)
      assert.equal(await a.evaluate(`document.querySelector(".project-facts").textContent.includes(${JSON.stringify(label)})`), true)
      await deleteCurrent(a)
      check(`${label} draft create and confirmed delete through UI`)
    }
    const bId = await createDraft(b, '网页', '第二位访客的草稿')
    assert.equal(await a.evaluate(`(await fetch('/api/projects/${bId}')).status`), 404)
    await deleteCurrent(b)
    check('ownership isolation works in both directions')
    await a.evaluate(`location.hash = "projects/${state.projectId}"`)
    await a.wait('document.querySelector(".project-detail h1")?.textContent === "MySQL 重启后仍保留"')
    await a.command('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
    assert.equal(await a.evaluate('document.documentElement.scrollWidth <= innerWidth'), true)
    await a.screenshot('projects-phone.png')
    check('390px detail has no horizontal overflow')
    await a.evaluate('location.hash = "home"')
    await a.wait('Boolean(document.querySelector("#project-name"))')
    assert.equal(await a.evaluate('document.documentElement.scrollWidth <= innerWidth'), true)
    await a.screenshot('home-phone.png')
    check('390px composer has no horizontal overflow')
    assert.equal(await a.evaluate('localStorage.length === 0 && sessionStorage.length === 0 && !document.cookie.includes("atoms_owner")'), true)
    check('owner credential is HttpOnly and absent from web storage')
    await a.evaluate(`location.hash = "projects/${state.projectId}"`)
    await a.wait('document.querySelector(".project-detail h1")?.textContent === "MySQL 重启后仍保留"')
    await writeFile(stateFile, JSON.stringify(state, null, 2) + '\n')
    await writeFile(resolve(evidence, 'browser-prepare-results.json'), JSON.stringify({
      startedAt: state.startedAt, recordedAt: new Date().toISOString(), browser: version.Browser,
      origin, mode: 'real UI + real API + MySQL; no response interception',
      checks: state.checks, passed: state.checks.length,
      pending: ['restart API and run finish with the same browser contexts'],
    }, null, 2) + '\n')
    console.log('Prepared. Restart API, then run finish with this same browser.')
  } else {
    await a.command('Page.reload')
    await a.wait('document.querySelector(".project-detail h1")?.textContent === "MySQL 重启后仍保留"')
    assert.equal(await a.evaluate('document.querySelector(".saved-prompt").textContent'), '真实数据库验收：保存需求，不调用模型。')
    check('API restart preserves visitor ownership name prompt and project UUID')
    assert.equal(await b.evaluate(`(await fetch('/api/projects/${state.projectId}')).status`), 404)
    check('API restart preserves cross-visitor denial')
    await a.screenshot('after-api-restart.png')
    await deleteCurrent(a)
    await a.wait('Boolean(document.querySelector(".empty-projects"))')
    await a.evaluate(`location.hash = "projects/${state.projectId}"`)
    await a.wait('document.querySelector(".project-error")?.textContent.includes("项目不存在")')
    check('confirmed deletion removes access to the former detail')
    await a.command('Page.reload')
    await a.wait('document.querySelector(".project-error")?.textContent.includes("项目不存在")')
    check('deleted state persists after reload')
    assert.deepEqual(exceptions, [])
    check('no uncaught page exceptions')
    const report = { startedAt: state.startedAt, completedAt: new Date().toISOString(), browser: version.Browser,
      origin, database: 'MySQL 8.0.36', mode: 'real UI + real API + MySQL; no response interception',
      checks: state.checks, passed: state.checks.length, failed: 0 }
    await writeFile(resolve(evidence, 'browser-results.json'), JSON.stringify(report, null, 2) + '\n')
    for (const context of [state.a, state.b]) await call('Target.disposeBrowserContext', { browserContextId: context.browserContextId })
    console.log(`PASS: ${report.passed} real browser checks; test drafts cleaned up.`)
  }
} finally {
  socket.close()
}
