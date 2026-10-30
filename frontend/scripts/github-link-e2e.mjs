// Isolated browser contract checks: all API and GitHub requests are mocked. No real mail or bindings.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import puppeteer from 'puppeteer-core'
import { createServer } from 'vite'
const root = process.cwd()
const output = path.join(root, '.screenshots/github-link')
fs.mkdirSync(output, { recursive: true })
const server = await createServer({ root, server: { host: '127.0.0.1', port: 5177, strictPort: true }, logLevel: 'error' })
await server.listen()
const chrome = ['C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(p => fs.existsSync(p))
const browser = await puppeteer.launch({ executablePath: chrome, headless: true })
const errors = []
const token = { accessToken: 'mock-access', user: { id: 42, username: 'existing', displayName: '同学', role: 'STUDENT', emailVerified: true, memberships: [] } }
try {
  for (const method of ['email', 'password']) {
    const context = await browser.createBrowserContext()
    const page = await context.newPage()
    await page.setViewport({ width: method === 'email' ? 390 : 1280, height: 844 })
    page.on('pageerror', e => errors.push(String(e)))
    let submitted
    await page.setRequestInterception(true)
    page.on('request', request => {
      const url = new URL(request.url())
      if (url.hostname === 'github.com') return request.respond({ status: 200, contentType: 'text/html', body: '<h1>Mock GitHub authorization</h1>' })
      if (!url.pathname.startsWith('/api/')) return request.continue()
      let data = null
      if (url.pathname.endsWith('/github/callback')) data = { mode: 'LINK_REQUIRED' }
      if (url.pathname.endsWith('/github/link-context')) data = { maskedEmail: 'ex***@example.test', githubLogin: 'original-github', hasPassword: true }
      if (url.pathname.endsWith('/github/link-login')) {
        submitted = JSON.parse(request.postData())
        data = { token, mode: 'BIND' }
      }
      if (url.pathname.endsWith('/refresh')) return request.respond({ status: 401, contentType: 'application/json', body: JSON.stringify({ success: false }) })
      request.respond({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data }) })
    })
    await page.evaluateOnNewDocument(() => sessionStorage.setItem('algohub.github.state', 'conflict-state'))
    await page.goto('http://127.0.0.1:5177/oauth/github/callback?code=mock-code&state=conflict-state', { waitUntil: 'networkidle0' })
    assert.match(await page.$eval('h1', el => el.textContent), /关联已有账号/)
    assert.equal(await page.$('button.card-button'), null)
    await page.screenshot({ path: path.join(output, `conflict-${method}.png`), fullPage: true })
    await page.click(method === 'email' ? 'a[href="/oauth/github/link"]' : 'a[href="/oauth/github/link?mode=password"]')
    await page.waitForSelector('form')
    assert.equal(await page.$('input[type=email]'), null)
    assert.equal(await page.$('input[autocomplete=username]'), null)
    assert.equal(await page.$eval('[data-test=locked-email]', el => el.textContent), 'ex***@example.test')
    if (method === 'email') {
      await page.click('.code-button')
      await page.waitForFunction(() => document.body.textContent.includes('验证码已发送'))
      await page.type('#github-link-code', '123456')
    } else {
      await page.type('input[type=password]', 'test-password')
    }
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
    await page.screenshot({ path: path.join(output, `form-${method}.png`), fullPage: true })
    await page.evaluate(async () => {
      const { default: router } = await import('/src/router/index.ts')
      router.beforeEach(to => ['/', '/select-school'].includes(to.path) ? false : true)
    })
    await Promise.all([page.waitForResponse(r => r.url().endsWith('/github/link-login')), page.click('button[type=submit]')])
    await page.waitForFunction(async () => (await import('/src/stores/auth.ts')).useAuthStore().me?.id === 42)
    assert.equal(submitted.email, undefined)
    assert.equal(submitted.username, undefined)
    assert.equal(submitted.method, method)
    assert.equal(method === 'email' ? submitted.code : submitted.password, method === 'email' ? '123456' : 'test-password')
    await context.close()
  }
  for (const endpoint of ['link-code', 'link-login']) {
    const context = await browser.createBrowserContext()
    const page = await context.newPage()
    page.on('pageerror', e => errors.push(String(e)))
    await page.setRequestInterception(true)
    page.on('request', request => {
      const url = new URL(request.url())
      if (!url.pathname.startsWith('/api/')) return request.continue()
      if (url.pathname.endsWith('/refresh')) return request.respond({ status: 401, contentType: 'application/json', body: JSON.stringify({ success: false }) })
      if (url.pathname.endsWith('/' + endpoint)) return request.respond({ status: 403, contentType: 'application/json', body: JSON.stringify({ success: false, errorKind: 'FORBIDDEN', message: '关联已过期，请重新授权' }) })
      request.respond({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: { maskedEmail: 'ex***@example.test', githubLogin: 'original-github', hasPassword: true } }) })
    })
    await page.goto('http://127.0.0.1:5177/oauth/github/link', { waitUntil: 'networkidle0' })
    if (endpoint === 'link-code') await page.click('.code-button')
    else { await page.type('#github-link-code', '123456'); await page.click('button[type=submit]') }
    await page.waitForSelector('[role=alert]')
    assert.equal(await page.$('form'), null)
    assert.match(await page.$eval('.link-card', el => el.textContent), /重新使用 GitHub 登录/)
    await context.close()
  }
  for (const mode of ['LOGIN', 'BIND', 'REAUTH']) {
    const context = await browser.createBrowserContext()
    const page = await context.newPage()
    page.on('pageerror', e => errors.push(String(e)))
    const requests = []
    await page.setRequestInterception(true)
    page.on('request', request => {
      const url = new URL(request.url())
      if (url.hostname === 'github.com') return request.respond({ status: 200, contentType: 'text/html', body: 'Mock authorization retry' })
      if (!url.pathname.startsWith('/api/')) return request.continue()
      requests.push(url.pathname + url.search)
      const refresh = url.pathname.endsWith('/refresh')
      if (refresh && mode === 'LOGIN') return request.respond({ status: 401, contentType: 'application/json', body: JSON.stringify({ success: false }) })
      if (url.pathname.endsWith('/github/link-context')) return request.respond({ status: 403, contentType: 'application/json', body: JSON.stringify({ success: false, message: '关联已过期，请重新授权' }) })
      const data = refresh ? token : { authorizeUrl: 'https://github.com/login/oauth/authorize?state=retry-state' }
      request.respond({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data }) })
    })
    await page.evaluateOnNewDocument(mode => {
      sessionStorage.setItem('algohub.github.state', 'cancelled-state')
      sessionStorage.setItem('algohub.github.intent', JSON.stringify({ mode, state: 'cancelled-state', expires: Date.now() + 600000, ...(mode === 'REAUTH' ? { purpose: 'FIRST_EMAIL' } : {}) }))
    }, mode)
    await page.goto('http://127.0.0.1:5177/oauth/github/callback?error=access_denied&state=cancelled-state', { waitUntil: 'networkidle0' })
    assert.match(await page.$eval('.callback-card', el => el.textContent), /取消了授权/)
    if (mode === 'BIND') {
      assert.equal(await page.$eval('button.card-button', el => el.textContent.trim()), '重新验证并关联')
      await page.click('button.card-button')
      await page.waitForSelector('[role=alert]')
      assert.equal(await page.$('form'), null)
      assert.equal(new URL(page.url()).pathname, '/oauth/github/link')
      assert.equal(requests.some(url => url.includes('/github/authorize-url')), false)
    } else {
      await Promise.all([page.waitForNavigation(), page.click('button.card-button')])
      assert.ok(requests.includes('/api/auth/github/authorize-url' + (mode === 'REAUTH' ? '?purpose=FIRST_EMAIL' : '')))
    }
    await context.close()
  }
  assert.deepEqual(errors, [])
  console.log('Fixed GitHub/email targets, password/code submit and expired/retry UI: passed (mock APIs)')
} finally { await browser.close(); await server.close() }
