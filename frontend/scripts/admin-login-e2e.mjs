import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import puppeteer from 'puppeteer-core'
import { createServer } from 'vite'
const root = process.cwd()
const server = await createServer({ root, server: { host: '127.0.0.1', port: 5177, strictPort: true }, logLevel: 'error' })
await server.listen()
const executablePath = ['C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(p => fs.existsSync(p))
const browser = await puppeteer.launch({ executablePath, headless: true })
const output = path.join(root, '.screenshots/admin-login')
fs.mkdirSync(output, { recursive: true })
const errors = []
try {
  const page = await browser.newPage()
  page.on('pageerror', e => errors.push(String(e)))
  let role = 'STUDENT'
  let loggedOut = false
  await page.setRequestInterception(true)
  page.on('request', request => {
    const url = new URL(request.url())
    if (!url.pathname.startsWith('/api/')) return request.continue()
    let data = { enabled: true }
    if (url.pathname.endsWith('/refresh')) return request.respond({ status: 401, contentType: 'application/json', body: JSON.stringify({ success: false }) })
    if (url.pathname.endsWith('/enrollment-years')) data = [2023, 2024, 2025, 2026]
    if (url.pathname.endsWith('/logout')) loggedOut = true
    if (url.pathname.endsWith('/login')) data = { accessToken: 'mock', user: { id: 1, role, emailVerified: true, memberships: [{ status: 'APPROVED' }] } }
    request.respond({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data }) })
  })
  await page.goto('http://127.0.0.1:5177/login', { waitUntil: 'networkidle0' })
  assert.doesNotMatch(await page.$eval('body', e => e.textContent), /管理端|管理登录|账号密码登录|用户名注册/)
  assert.equal(await page.$('a[href="/admin/login"]'), null)
  await page.goto('http://127.0.0.1:5177/login?mode=password', { waitUntil: 'networkidle0' })
  assert.equal(await page.$('input[type=password]'), null)
  assert.equal(await page.$('a[href="/forgot-password"]'), null)
  await page.screenshot({ path: path.join(output, 'public-login.png'), fullPage: true })
  await page.goto('http://127.0.0.1:5177/admin', { waitUntil: 'networkidle0' })
  assert.equal(new URL(page.url()).pathname, '/admin/login')
  assert.match(await page.$eval('h2', e => e.textContent), /管理登录/)
  assert.doesNotMatch(await page.$eval('.form-panel', e => e.textContent), /用用户名注册/)
  await page.setViewport({ width: 390, height: 844 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await page.screenshot({ path: path.join(output, 'mobile.png'), fullPage: true })
  await page.type('#login-username', 'test-user')
  await page.type('#login-password', 'test-password')
  await page.click('button[type=submit]')
  await page.waitForFunction(() => document.body.textContent.includes('没有管理权限'))
  assert.equal(loggedOut, true)
  assert.equal(await page.evaluate(async () => (await import('/src/stores/auth.ts')).useAuthStore().isLoggedIn), false)
  await page.evaluate(async () => {
    const { default: router } = await import('/src/router/index.ts')
    router.beforeEach(to => { if (to.path === '/admin' || to.path === '/') { window.__destination = to.path; return false } return true })
  })
  role = 'ADMIN'
  await page.type('#login-password', 'test-password')
  await page.click('button[type=submit]')
  await page.waitForFunction(() => window.__destination === '/admin')
  // A student visiting the protected route is redirected before the admin view loads.
  await page.evaluate(async () => {
    const auth = (await import('/src/stores/auth.ts')).useAuthStore()
    auth.apply({ accessToken: 'student', user: { id: 2, role: 'STUDENT', emailVerified: true, memberships: [{ status: 'APPROVED' }] } })
    window.__destination = null
    await (await import('/src/router/index.ts')).default.push('/admin')
  })
  await page.waitForFunction(() => window.__destination === '/')
  assert.deepEqual(errors, [])
  console.log('Public entry, separate admin login, student rejection, admin redirect and mobile layout passed (mock API).')
} finally { await browser.close(); await server.close() }
