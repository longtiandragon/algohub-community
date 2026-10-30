import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import puppeteer from 'puppeteer-core'

// Use local Vite. All APIs and external requests are intercepted; no real account is used.
const origin = process.env.ALGOHUB_PREVIEW_URL || 'http://127.0.0.1:5188'
assert.ok(['127.0.0.1', 'localhost'].includes(new URL(origin).hostname), 'This regression only runs against localhost')
const browser = await puppeteer.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
const user = { id: 71, username: 'logout-fixture', displayName: '隔离测试账号', role: 'ADMIN', email: 'fixture@example.test', emailVerified: true, onboardingCompleted: true, memberships: [], bindings: [], privacy: { profileVisibility: 'PUBLIC', showOnRanking: true, showAccounts: true } }
const token = (id = user.id) => ({ accessToken: `fixture-token-${id}`, user: { ...user, id } })
const key = 'algohub.pending-logout'
const shots = new URL('../.screenshots/security-fixes/', import.meta.url)
await fs.mkdir(shots, { recursive: true })
const list = { id: 5, code: 'fixture', title: '隐藏题单测试', description: '', category: null, platform: null, source: 'MANUAL', visible: false, ownerId: 99, shareScope: 'PRIVATE', itemCount: 0, solvedCount: 0, favoriteCount: 0 }
const item = { itemId: 1, position: 1, problemId: 23, platform: 'CODEFORCES', problemKey: '1A', title: 'Fixture problem', url: null, difficultyLabel: null, difficultyValue: null, solvedCount: null, tags: [], solvedByMe: false }
const detail = { list, items: [item], itemCount: 1, solvedCount: 0, note: '' }

async function setup() {
  const context = await browser.createBrowserContext()
  const state = { logout: 'ok', refresh: 'ok', refreshUser: user.id, loginUser: user.id, cookie: true, calls: [], heldRefresh: null, hold: false, heldLogin: null, holdLogin: false, identityExpired: false, rejectOldPrivacy: false, permissionFull: true, permissions: ['PROBLEMS', 'SYNC'] }
  async function page() {
    const page = await context.newPage()
    page.on('pageerror', error => console.error('Mock page error:', error.message))
    await page.setViewport({ width: 1200, height: 860 })
    await page.setRequestInterception(true)
    page.on('request', async request => {
      const url = new URL(request.url())
      if (url.origin !== new URL(origin).origin) { await request.abort(); return }
      if (!url.pathname.startsWith('/api/')) { await request.continue(); return }
      state.calls.push({ path: url.pathname, method: request.method(), auth: request.headers().authorization, body: request.postData() })
      let status = 200, data = []
      if (url.pathname === '/api/auth/refresh') {
        if (state.hold) { state.hold = false; await new Promise(resolve => { state.heldRefresh = resolve }) }
        if (state.refresh === 'network') { await request.abort(); return }
        status = state.cookie && state.refresh !== '401' ? 200 : 401
        data = token(state.refreshUser)
      } else if (url.pathname === '/api/auth/logout' || url.pathname === '/api/auth/logout-all') {
        if (state.logout === 'network') { await request.abort(); return }
        if (state.logout === 'expired' && request.headers().authorization === `Bearer fixture-token-${user.id}`) { status = 401; state.logout = 'ok' }
        else state.cookie = false
        data = null
      } else if (url.pathname === '/api/auth/email/login') {
        if (state.holdLogin) { state.holdLogin = false; await new Promise(resolve => { state.heldLogin = resolve }) }
        state.cookie = true
        state.refreshUser = state.loginUser
        data = token(state.loginUser)
      } else if (url.pathname === '/api/me/privacy') {
        if (state.rejectOldPrivacy && request.headers().authorization === `Bearer fixture-token-${user.id}`) status = 401
        data = null
      } else if (url.pathname === '/api/me/login-email/verify') {
        if (state.identityExpired) { status = 401; state.identityExpired = false }
        data = token()
      } else if (url.pathname === '/api/me') data = user
      else if (url.pathname === '/api/auth/github/status') data = { enabled: false, note: '' }
      else if (url.pathname === '/api/auth/email/status') data = { enabled: true }
      else if (url.pathname === '/api/auth/enrollment-years') data = [2026]
      else if (url.pathname === '/api/admin/me') data = { userId: user.id, full: state.permissionFull, permissions: state.permissions, catalog: [] }
      else if (url.pathname === '/api/activities') data = { items: [], note: '' }
      else if (url.pathname === '/api/admin/problem-lists') data = [list]
      else if (['/api/admin/problem-lists/5', '/api/problem-lists/5'].includes(url.pathname) && request.method() === 'GET') data = detail
      else if (url.pathname === '/api/problem-lists/5/leaderboard') data = { rows: [], note: '', itemCount: 0 }
      else if (url.pathname === '/api/admin/problem-lists/problems') data = [{ id: 24, platform: 'CODEFORCES', problemKey: '1B', title: 'Fixture add problem' }]
      else if (url.pathname.endsWith('/items')) data = { added: 1 }
      else if (url.pathname === '/api/stats/profile') data = { snapshotOverview: [], platformSolved: [], platformTotals: [], recentAccepted: [], totalPassed: 1, totalSolvedAcrossPlatforms: 1 }
      else if (url.pathname.startsWith('/api/history')) data = { rows: [{ id: 8, platform: 'LUOGU', problemKey: 'P1000', title: '导入测试题', submittedAt: '2026-10-02T00:00:00Z', timePrecision: 'USER_REPORTED', verdict: 'AC', accepted: true, recordKind: 'SUBMISSION' }], total: 1 }
      await request.respond({ status, contentType: 'application/json', body: JSON.stringify({ success: status === 200, data, errorKind: status === 401 ? 'UNAUTHENTICATED' : null, message: status === 401 ? 'Session expired' : null }) })
    })
    return page
  }
  const first = await page()
  await first.goto(origin + '/announcements', { waitUntil: 'networkidle0' })
  await first.waitForSelector('[data-test="page"]')
  return { context, state, page: first, newPage: page }
}
async function store(page) {
  await page.evaluate(async () => {
    const urls = performance.getEntriesByType('resource').map(r => r.name)
    window.testAuth = (await import(urls.findLast(url => new URL(url).pathname === '/src/stores/auth.ts') || '/src/stores/auth.ts')).useAuthStore()
    for (const url of [...new Set(urls.filter(url => new URL(url).pathname === '/src/api/client.ts'))].reverse()) {
      const client = await import(url)
      if (client.getAccessToken() || client.pendingLogout.value) { window.testClient = client; break }
    }
    window.testClient ??= await import('/src/api/client.ts')
  })
}
async function logout(page, all = false) {
  await store(page)
  return page.evaluate(async all => { try { await (all ? window.testAuth.logoutAll() : window.testAuth.logout()); return 'ok' } catch (e) { return e.message } }, all)
}
async function pending(page) { await page.waitForSelector('[data-test="pending-logout"]') }
async function loggedOut(page) { await page.waitForFunction(() => ['/login', '/admin/login'].includes(location.pathname)) }
async function shot(page, name) { await page.screenshot({ path: fileURLToPath(new URL(name, shots)) }) }
async function clickText(page, selector, text) {
  try {
    await page.waitForFunction((selector, text) => [...document.querySelectorAll(selector)].some(el => el.textContent.trim() === text), {}, selector, text)
  } catch (error) {
    console.error('UI action not found', { selector, text, buttons: await page.$$eval('button', buttons => buttons.map(b => b.textContent.trim())) })
    await shot(page, 'regression-failure.png')
    throw error
  }
  await page.evaluate((selector, text) => [...document.querySelectorAll(selector)].find(el => el.textContent.trim() === text).click(), selector, text)
}

try {
  {
    const x = await setup()
    const second = await x.newPage()
    await second.goto(origin + '/announcements', { waitUntil: 'networkidle0' })
    await store(x.page)
    await store(second)
    x.state.loginUser = 72
    await second.evaluate(async () => window.testAuth.apply(await window.testClient.post('/auth/email/login', { email: 'other@example.test', code: '000000' })))
    x.state.rejectOldPrivacy = true
    const result = await x.page.evaluate(async () => {
      try { await window.testClient.post('/me/privacy', { showAccounts: false }); return 'replayed' }
      catch (error) { return error.kind }
    })
    assert.equal(result, 'SESSION_CHANGED', 'An old account request must not be retried with another account refresh token')
    const writes = x.state.calls.filter(call => call.path === '/api/me/privacy')
    assert.equal(writes.length, 1, 'The old form is never submitted as the other account')
    assert.equal(writes[0].auth, `Bearer fixture-token-${user.id}`)
    assert.equal(await x.page.evaluate(() => window.testAuth.me), null)
    assert.equal(await x.page.evaluate(() => window.testClient.getAccessToken()), null)
    assert.equal(await second.evaluate(() => window.testAuth.me.id), 72, 'The newly logged-in account remains available in its own tab')
    await x.context.close()
    console.log('PASS cross-tab account switch: stale write is rejected without replay under the new cookie identity')
  }
  {
    const x = await setup()
    await store(x.page)
    x.state.rejectOldPrivacy = true
    x.state.hold = true
    await x.page.evaluate(() => { window.staleWrite = window.testClient.post('/me/privacy', { showAccounts: false }).catch(error => error.kind) })
    while (!x.state.heldRefresh) await new Promise(resolve => setTimeout(resolve, 20))
    await x.page.evaluate(proof => window.testAuth.apply(proof), token(72))
    x.state.heldRefresh()
    assert.equal(await x.page.evaluate(() => window.staleWrite), 'SESSION_CHANGED')
    assert.equal(await x.page.evaluate(() => window.testAuth.me.id), 72, 'An obsolete 401 must not clear a newer login')
    assert.equal(await x.page.evaluate(() => window.testClient.getAccessToken()), 'fixture-token-72')
    assert.equal(x.state.calls.filter(call => call.path === '/api/me/privacy').length, 1)
    await x.context.close()
    console.log('PASS obsolete 401 recovery: pending old refresh neither replays a write nor clears a newer identity')
  }
  {
    const x = await setup()
    const second = await x.newPage()
    await second.evaluateOnNewDocument(() => {
      window.delayedStorage = []
      window.addEventListener('storage', event => {
        if (event.key === 'algohub.pending-logout' && !window.forwardStorage) { event.stopImmediatePropagation(); window.delayedStorage.push({ key: event.key, newValue: event.newValue }) }
      }, true)
    })
    await second.goto(origin + '/announcements', { waitUntil: 'networkidle0' })
    await store(second)
    x.state.logout = 'network'
    await logout(x.page, true)
    assert.equal(await second.evaluate(() => window.testAuth.pendingLogout), null, 'Storage event delivery is intentionally delayed')
    await logout(second)
    assert.equal(await second.evaluate(key => JSON.parse(localStorage.getItem(key)).mode, key), 'all')
    assert.equal(await second.evaluate(() => window.testAuth.pendingLogout.mode), 'all')
    assert.equal(x.state.calls.filter(c => c.path === '/api/auth/logout').length, 0)
    await second.evaluate(() => { window.forwardStorage = true; for (const event of window.delayedStorage) window.dispatchEvent(new StorageEvent('storage', event)); window.forwardStorage = false })
    assert.equal(await second.evaluate(() => window.testAuth.pendingLogout.mode), 'all')
    await x.context.close()
    console.log('PASS delayed cross-tab events: current logout cannot overwrite an already-persisted all-device logout')
  }
  {
    const x = await setup()
    const second = await x.newPage()
    await second.evaluateOnNewDocument(() => {
      window.addEventListener('storage', event => { if (event.key === 'algohub.pending-logout' && !window.forwardStorage) event.stopImmediatePropagation() }, true)
    })
    await second.goto(origin + '/announcements', { waitUntil: 'networkidle0' })
    await store(second)
    x.state.logout = 'network'
    await logout(second)
    const id = await second.evaluate(() => window.testAuth.pendingLogout.id)
    await second.evaluate(() => {
      window.forwardStorage = true
      window.dispatchEvent(new StorageEvent('storage', { key: 'algohub.pending-logout', newValue: null }))
      window.dispatchEvent(new StorageEvent('storage', { key: 'algohub.pending-logout', newValue: JSON.stringify({ id: 'old', mode: 'current', userId: 71 }) }))
      window.forwardStorage = false
    })
    assert.equal(await second.evaluate(() => window.testAuth.pendingLogout.id), id)
    await pending(second)
    x.state.logout = 'ok'
    await second.evaluate(() => window.dispatchEvent(new Event('online')))
    await loggedOut(second)
    assert.equal(await second.evaluate(key => localStorage.getItem(key), key), null)
    await x.context.close()
    console.log('PASS stale storage set/remove events: the latest durable marker remains blocking and online retryable')
  }
  {
    const x = await setup()
    await store(x.page)
    x.state.holdLogin = true
    await x.page.evaluate(() => { window.loginDone = window.testClient.post('/auth/email/login', { email: 'fixture@example.test', code: '000000' }).then(t => window.testAuth.apply(t)).catch(e => e.kind) })
    while (!x.state.heldLogin) await new Promise(resolve => setTimeout(resolve, 20))
    await x.page.evaluate(() => { window.logoutDone = window.testAuth.logout() })
    x.state.heldLogin()
    assert.equal(await x.page.evaluate(async () => await window.loginDone), 'SESSION_CHANGED')
    await x.page.evaluate(async () => await window.logoutDone)
    assert.equal(x.state.cookie, false, 'A late login cookie must be revoked after the response arrives')
    await x.context.close()
    console.log('PASS late login/logout race: cookie-issuing response completes before revocation; no identity restores')
  }
  {
    const x = await setup()
    await store(x.page)
    x.state.identityExpired = true
    const result = await x.page.evaluate(async () => await window.testClient.post('/me/login-email/verify', {}))
    assert.equal(result.user.id, user.id)
    assert.equal(x.state.calls.filter(c => c.path === '/api/me/login-email/verify').length, 2)
    await x.context.close()
    console.log('PASS identity cookie rotation: 401 refresh and retry within the lock does not deadlock')
  }
  {
    const x = await setup()
    await x.page.evaluate(() => sessionStorage.setItem('algohub-onboarding-71', JSON.stringify({ studentNo: 'fixture', name: 'fixture' })))
    x.state.logout = 'network'
    assert.match(await logout(x.page), /服务端退出尚未完成/)
    await pending(x.page)
    assert.equal(await x.page.evaluate(() => sessionStorage.getItem('algohub-onboarding-71')), null)
    const marker = await x.page.evaluate(key => JSON.parse(localStorage.getItem(key)), key)
    assert.deepEqual(Object.keys(marker).sort(), ['id', 'mode', 'userId'])
    assert.equal(marker.mode, 'current')
    assert.equal(await x.page.evaluate(() => window.testClient.getAccessToken()), null)
    assert.ok(x.state.calls.some(c => c.path === '/api/auth/logout' && c.auth === `Bearer fixture-token-${user.id}`))
    await shot(x.page, 'logout-pending.png')
    const before = x.state.calls.filter(c => c.path === '/api/auth/refresh').length
    await x.page.reload({ waitUntil: 'networkidle0' })
    await pending(x.page)
    assert.equal(x.state.calls.filter(c => c.path === '/api/auth/refresh').length, before, 'Reload must not restore an unfinished logout')
    x.state.logout = 'ok'
    await x.page.evaluate(() => window.dispatchEvent(new Event('online')))
    await loggedOut(x.page)
    assert.equal(await x.page.evaluate(key => localStorage.getItem(key), key), null)
    await x.context.close()
    console.log('PASS network failure: blocker, no credentials in marker, draft cleanup, reload blocked, online retry succeeds')
  }
  {
    const x = await setup()
    await store(x.page)
    x.state.hold = true
    await x.page.evaluate(() => { window.refreshDone = window.testClient.refreshSession() })
    while (!x.state.heldRefresh) await new Promise(resolve => setTimeout(resolve, 20))
    await x.page.evaluate(() => { window.logoutDone = window.testAuth.logout() })
    await pending(x.page)
    x.state.heldRefresh()
    assert.equal(await x.page.evaluate(async () => await window.refreshDone), null)
    await x.page.evaluate(async () => await window.logoutDone)
    assert.equal(await x.page.evaluate(() => window.testAuth.me), null)
    assert.equal(await x.page.evaluate(() => window.testClient.getAccessToken()), null)
    assert.equal(x.state.cookie, false)
    await x.context.close()
    console.log('PASS refresh/logout race: stale response discarded, rotated cookie revoked, identity stays cleared')
  }
  {
    const x = await setup()
    const second = await x.newPage()
    await second.goto(origin + '/announcements', { waitUntil: 'networkidle0' })
    await store(second)
    await logout(x.page)
    await second.waitForFunction(() => window.testAuth.me === null && window.testClient.getAccessToken() === null)
    await x.context.close()
    console.log('PASS cross-tab logout: other identity clears even when marker is immediately removed')
  }
  {
    const x = await setup()
    x.state.logout = 'network'
    await logout(x.page, true)
    assert.equal(await x.page.evaluate(key => JSON.parse(localStorage.getItem(key)).mode, key), 'all')
    x.state.logout = 'ok'
    x.state.refreshUser = 99
    await x.page.reload({ waitUntil: 'networkidle0' })
    await x.page.waitForSelector('[data-test="logout-identity"]')
    await shot(x.page, 'logout-original-account.png')
    await store(x.page)
    const count = x.state.calls.filter(c => c.path === '/api/auth/logout-all').length
    const mismatch = await x.page.evaluate(async proof => { try { await window.testAuth.apply(proof); return 'ok' } catch (e) { return e.message } }, token(99))
    assert.match(mismatch, /原账号/)
    assert.equal(x.state.calls.filter(c => c.path === '/api/auth/logout-all').length, count, 'Wrong-account token must never revoke that account')
    assert.equal(await x.page.evaluate(async proof => await window.testAuth.apply(proof), token()), false)
    assert.equal(await x.page.evaluate(() => window.testAuth.me), null)
    assert.equal(x.state.calls.filter(c => c.path === '/api/auth/logout').length, 0, 'All-device logout must never degrade to current logout')
    await x.context.close()
    console.log('PASS logout-all recovery: scope preserved, wrong identity rejected, original identity revokes without login')
  }
  {
    const x = await setup()
    x.state.logout = 'expired'
    assert.equal(await logout(x.page), 'ok')
    assert.equal(x.state.cookie, false)
    assert.match(await logout(x.page, true), /没有可验证的账号/)
    assert.equal(await x.page.evaluate(key => localStorage.getItem(key), key), null, 'A stale all-device click without an identity must not create an unfinishable marker')
    await x.context.close()
    console.log('PASS expired access token: current logout retries cookie revocation without restoring login')
  }
  {
    const x = await setup()
    await store(x.page)
    await x.page.evaluate(async proof => {
      const { draft } = (await import('/src/composables/useOnboardingDraft.ts')).useOnboardingDraft()
      window.fixtureDraft = draft
      Object.assign(draft, { displayName: 'fixture', studentNo: 'fixture', className: 'fixture', inviteCode: 'fixture' })
      await window.testAuth.apply(proof)
    }, token(72))
    assert.equal(await x.page.evaluate(() => sessionStorage.getItem('algohub-onboarding-71')), null)
    assert.deepEqual(await x.page.evaluate(() => [window.fixtureDraft.displayName, window.fixtureDraft.studentNo, window.fixtureDraft.className, window.fixtureDraft.inviteCode]), ['', '', '', ''])
    await x.page.evaluate(() => { window.fixtureDraft.studentNo = 'must-not-save' })
    assert.equal(await x.page.evaluate(() => sessionStorage.getItem('algohub-onboarding-71')), null)
    await x.context.close()
    console.log('PASS identity switch clears both persisted onboarding data and live draft references; old watcher cannot save again')
  }
  {
    const x = await setup()
    await x.page.goto(origin + '/problem-lists/5', { waitUntil: 'networkidle0' })
    assert.equal(await x.page.$('[data-test="list-more"]'), null)
    assert.equal(await x.page.$('[data-test="add-problems"]'), null)
    await x.page.goto(origin + '/admin/problem-lists/5', { waitUntil: 'networkidle0' })
    await x.page.waitForSelector('[data-test="list-more"]')
    assert.ok(await x.page.$('[data-test="add-problems"]'))
    assert.equal(await x.page.$('[data-test="list-favorite"]'), null)
    assert.ok(x.state.calls.some(c => c.path === '/api/admin/problem-lists/5'))
    await shot(x.page, 'admin-list-detail.png')
    await x.page.click('[data-test="list-more"]')
    await clickText(x.page, '.el-dropdown-menu__item', '编辑题单')
    await x.page.waitForSelector('[data-test="edit-title"]')
    await x.page.click('[data-test="edit-title"]', { clickCount: 3 })
    await x.page.type('[data-test="edit-title"]', '修改测试题单')
    await x.page.click('[data-test="edit-submit"]')
    await x.page.waitForFunction(() => !document.querySelector('[data-test="edit-submit"]') || document.querySelector('[data-test="edit-submit"]').offsetParent === null)
    assert.ok(x.state.calls.some(c => c.path === '/api/admin/problem-lists/5' && c.method === 'POST'))
    await clickText(x.page, '[data-test="add-problems"] button', '搜索')
    await clickText(x.page, '[data-test="add-problems"] button', '加入')
    await x.page.waitForFunction(() => document.body.innerText.includes('已加入'))
    assert.ok(x.state.calls.some(c => c.path === '/api/admin/problem-lists/5/items' && c.method === 'POST'))
    await x.page.click('[data-item="23"] .remove-btn')
    await clickText(x.page, '.el-message-box__btns button', '移出')
    await x.page.waitForFunction(() => document.body.innerText.includes('已移出题单'))
    assert.ok(x.state.calls.some(c => c.path === '/api/admin/problem-lists/5/items/23' && c.method === 'DELETE'))
    await x.page.goto(origin + '/admin/problem-lists', { waitUntil: 'networkidle0' })
    await x.page.click('[data-test="create-list"]')
    await x.page.waitForSelector('[data-test="create-title"]')
    await x.page.type('[data-test="create-title"]', '系统题单测试')
    await x.page.click('[data-test="create-submit"]')
    await x.page.waitForFunction(() => document.body.innerText.includes('题单已创建'))
    await x.page.waitForFunction(() => document.querySelector('[data-test="create-submit"]')?.offsetParent === null)
    assert.ok(x.state.calls.some(c => c.path === '/api/admin/problem-lists' && c.method === 'POST' && JSON.parse(c.body).shareScope === 'PUBLIC'))
    await x.page.click('[aria-label="删除「隐藏题单测试」"]')
    await clickText(x.page, '.el-message-box__btns button', '删除')
    await x.page.waitForFunction(() => document.body.innerText.includes('题单已删除'))
    assert.ok(x.state.calls.some(c => c.path === '/api/admin/problem-lists/5' && c.method === 'DELETE'))
    await x.context.close()
    console.log('PASS list boundary and admin CRUD: owner checks, hidden detail, create/edit/add/remove/delete use dedicated admin APIs')
  }
  {
    const x = await setup()
    await x.page.goto(origin + '/profile?tab=records', { waitUntil: 'networkidle0' })
    await x.page.waitForFunction(() => document.body.innerText.includes('导入测试题'))
    assert.equal(await x.page.$$eval('[data-test="user-reported"]', els => els.length), 0)
    await shot(x.page, 'user-reported-history.png')
    await x.page.setViewport({ width: 390, height: 844 })
    await x.page.waitForSelector('[data-test="history-group"]')
    assert.equal(await x.page.$$eval('[data-test="user-reported"]', els => els.length), 0)
    await shot(x.page, 'user-reported-history-mobile.png')
    await x.page.goto(origin + '/luogu-sync', { waitUntil: 'networkidle0' })
    assert.match(await x.page.$eval('[data-test="page"]', el => el.textContent), /无法独立核验上传内容/)
    assert.match(await x.page.$eval('[data-test="page"]', el => el.textContent), /额外评测题也计入洛谷通过题数/)
    assert.match(await x.page.$eval('[data-test="page"]', el => el.textContent), /有通过时间的记录参与近期排行和动态/)
    x.state.permissionFull = false
    x.state.permissions = ['SYNC']
    await x.page.goto(origin + '/activities', { waitUntil: 'networkidle0' })
    assert.equal(await x.page.$$eval('button', buttons => buttons.some(b => b.textContent.trim() === '发布活动')), false)
    x.state.permissions = ['PROBLEMS']
    await x.page.reload({ waitUntil: 'networkidle0' })
    assert.equal(await x.page.$$eval('button', buttons => buttons.some(b => b.textContent.trim() === '发布活动')), true)
    x.state.permissionFull = true
    x.state.permissions = []
    await x.page.reload({ waitUntil: 'networkidle0' })
    assert.equal(await x.page.$$eval('button', buttons => buttons.some(b => b.textContent.trim() === '发布活动')), true)
    await x.context.close()
    console.log('PASS browser-import desktop/mobile records without redundant labels and honest import description; activity controls require PROBLEMS or full admin')
  }
} finally { await browser.close() }
