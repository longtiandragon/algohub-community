/**
 * 仅操作隔离的 demo 数据库，所有邮件自动发送关闭。需先 scripts/start-demo.ps1。
 * 示例同学没有密码登录，走只在 demo 配置下存在的 /api/auth/demo-login；管理员用密码。
 * 后端地址可用 ALGOHUB_API_TARGET 覆盖（默认 8081），本脚本起的 Vite 端口可用 E2E_PORT 覆盖（默认 5176）。
 */
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import puppeteer from 'puppeteer-core'
import { createServer } from 'vite'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const output = path.join(root, '.screenshots/reminders-e2e')
fs.mkdirSync(output, { recursive: true })
process.env.ALGOHUB_API_TARGET ||= 'http://127.0.0.1:8081'
const port = Number(process.env.E2E_PORT || 5176)
const server = await createServer({ root, configFile: path.join(root, 'vite.config.ts'), server: { host: '127.0.0.1', port, strictPort: true }, logLevel: 'error' })
await server.listen()
const chrome = [process.env.CHROME_PATH, 'C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(p => p && fs.existsSync(p))
const browser = await puppeteer.launch({ executablePath: chrome, headless: true, args: ['--no-sandbox'] })
const base = `http://127.0.0.1:${port}`
const errors = []
async function login(username, password, route) {
  const context = await browser.createBrowserContext()
  const page = await context.newPage()
  page.on('pageerror', e => errors.push(String(e)))
  await page.setViewport({ width: 1440, height: 1000 })
  await page.goto(`${base}/api/auth/email/status`)
  const loggedIn = await page.evaluate(async (username, password) => {
    const res = await fetch(password == null ? '/api/auth/demo-login' : '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(password == null ? { username } : { username, password }) })
    return (await res.json()).success
  }, username, password ?? null)
  assert.equal(loggedIn, true, `login failed: ${username}`)
  await page.goto(`${base}${route}`, { waitUntil: 'networkidle0' })
  return page
}
async function api(page, path) {
  return page.evaluate(async path => (await import('/src/api/client.ts')).get(path), path)
}
async function clickText(page, text) {
  const clicked = await page.evaluate(text => {
    const element = [...document.querySelectorAll('button')].find(b => b.textContent.trim() === text)
    if (!element) return false
    element.click(); return true
  }, text)
  assert.equal(clicked, true, `missing button: ${text}`)
}
async function fill(page, selector, value) {
  await page.waitForSelector(selector)
  await page.$eval(selector, (el, value) => { el.focus(); el.value = value; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); el.blur() }, String(value))
}
async function mutation(page, suffix, click) {
  const response = page.waitForResponse(r => r.url().endsWith(suffix) && r.request().method() === 'POST')
  await click()
  const data = await (await response).json()
  assert.equal(data.success, true, data.message)
  return data.data
}
try {
  const student = await login('chenyiming', null, '/reminders')
  const state = await api(student, '/reminders/status')
  assert.equal(state.enabled, false, 'This test must only run against demo with dispatch disabled')
  await fill(student, 'input[aria-label="每周计划题数"]', 15)
  await fill(student, 'input[aria-label="每月计划题数"]', 70)
  await mutation(student, '/reminders/goals', () => clickText(student, '保存计划'))
  const goals = await api(student, '/reminders/goals')
  assert.equal(goals.weekly.personal, 15); assert.equal(goals.monthly.personal, 70)
  assert.equal(goals.monthly.target, Math.max(70, goals.monthly.required))

  const admin = await login('admin', 'admin12345', '/admin?tab=reminders')
  assert.deepEqual(await api(admin, '/auth/enrollment-years'), [2026, 2025, 2024, 2023])
  await admin.goto(`${base}/admin?tab=years`, { waitUntil: 'networkidle0' })
  await mutation(admin, '/admin/enrollment-years', () => clickText(admin, '保存2023级'))
  assert.equal((await api(admin, '/admin/enrollment-years')).find(r => r.year === 2023).monthlyTarget, 0)
  await admin.goto(`${base}/admin?tab=reminders`, { waitUntil: 'networkidle0' })
  await fill(admin, 'input[aria-label="搜索提醒对象"]', 'chenyiming')
  const found = admin.waitForResponse(r => r.url().includes('/admin/reminders/users?') && r.url().includes('chenyiming'))
  await clickText(admin, '搜索'); await found
  await admin.waitForFunction(() => document.querySelectorAll('button.member').length === 1)
  await admin.click('button.member')
  await fill(admin, 'input[aria-label="管理员每周要求"]', 18)
  await fill(admin, 'input[aria-label="管理员每月要求"]', 80)
  const users = await api(admin, '/admin/reminders/users?search=chenyiming')
  const uid = users[0].id
  await mutation(admin, `/users/${uid}/goals`, () => clickText(admin, '保存并通知'))
  await admin.waitForSelector('textarea[aria-label="管理员提醒留言"]')
  await fill(admin, 'textarea[aria-label="管理员提醒留言"]', '本周记得练习并同步平台记录。')
  await mutation(admin, `/users/${uid}/send`, () => clickText(admin, `发送提醒给 ${users[0].displayName}`))
  await student.reload({ waitUntil: 'networkidle0' })
  assert.equal((await api(student, '/reminders/goals')).monthly.target, 80)
  const inbox = await api(student, '/reminders/inbox')
  assert(inbox.items.some(n => n.body.includes('本周记得练习并同步平台记录。')))
  assert(inbox.unread >= 1)
  await student.goto(`${base}/messages`, { waitUntil: 'networkidle0' })
  await mutation(student, '/inbox/read-all?category=ALL', () => clickText(student, '全部已读'))
  assert.equal((await api(student, '/reminders/unread')).unread, 0)
  for (const width of [390, 834, 1440]) {
    for (const [name, page] of [['student', student], ['admin', admin]]) {
      await page.setViewport({ width, height: width === 390 ? 844 : 1000 })
      await new Promise(r => setTimeout(r, 200))
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1), `${name} overflows ${width}`)
      await page.screenshot({ path: path.join(output, `${name}-${width}.png`), fullPage: true })
    }
  }
  assert.deepEqual(errors, [])
  console.log('PASS: personal goals, admin assignment, manual message, inbox, read-all, and 6 responsive screenshots. Demo only; no external mail sent.')
} finally { await browser.close(); await server.close() }
