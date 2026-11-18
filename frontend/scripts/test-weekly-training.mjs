import assert from 'node:assert/strict'
import { existsSync } from 'node:fs'
import puppeteer from 'puppeteer-core'
import { preview } from 'vite'

// Run after npm run build. Exercise the production entry points with synthetic school data.
const executablePath = [process.env.CHROME_PATH, 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', '/usr/bin/google-chrome', '/usr/bin/chromium'].find(p => p && existsSync(p))
assert.ok(executablePath, 'Set CHROME_PATH to run the weekly training browser regression')
const server = await preview({ preview: { port: 0, host: '127.0.0.1' } })
const base = 'http://127.0.0.1:' + server.httpServer.address().port
let browser
try {
  browser = await puppeteer.launch({ executablePath, headless: true })
  const page = await browser.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  let management = true, submitted = null
  const meta = { platform: 'CODEFORCES', ref: '2278', title: 'Codeforces Round 1131 (Div. 3)', category: 'Div.3',
    url: 'https://codeforces.com/contest/2278', tasks: [...'ABCDEFG'].map(index => ({ index, key: '2278' + index })) }
  const targets = { 2026: 4, 2025: 5 }
  const user = { id: 3, username: 'fixture', displayName: '测试账号', role: 'STUDENT', emailVerified: true, onboardingCompleted: true,
    memberships: [{ schoolId: 1, schoolName: '测试学校', status: 'APPROVED', enrollmentYear: 2026 }], managedCohorts: [{ schoolId: 1, enrollmentYear: 2026 }] }
  const students = [{ userId: 4, year: 2026, name: '测试甲', solved: 5, goal: 4, status: 'MET' },
    { userId: 5, year: 2025, name: '测试乙', solved: 0, goal: 5, status: 'UNBOUND' }]
  const cell = { solved: true, wrong: 2, score: 1, seconds: 83 }
  const rows = [{ userId: 4, name: '测试甲', kind: 'VIRTUAL', rank: 1, cells: { A: cell }, score: 1, penalty: 83 },
    { userId: 4, name: '测试甲', kind: 'PRACTICE', rank: null, cells: { E: cell }, score: null, penalty: null }]
  const contest = () => ({ id: 6, meta, targets, weekStart: submitted.weekStart, students })
  await page.setRequestInterception(true)
  page.on('request', req => {
    const url = new URL(req.url()), path = url.pathname
    if (!path.startsWith('/api/')) return req.continue()
    let data = [], status = 200
    if (path === '/api/auth/refresh') data = { accessToken: 'mock', user }
    else if (path === '/api/me') data = user
    else if (path === '/api/weekly/schools') data = [{ id: 1, name: '测试学校', manage: management }]
    else if (path === '/api/weekly/1/preview') data = { meta, targets, years: [2025, 2026] }
    else if (path === '/api/weekly/1' && req.method() === 'POST') { submitted = JSON.parse(req.postData()); data = 6 }
    else if (path === '/api/weekly/1') data = submitted ? [contest()] : []
    else if (path === '/api/weekly/1/6') data = { ...contest(), rows, problemStats: { A: { solved: 1, tried: 1 }, E: { solved: 1, tried: 1 } } }
    else if (path.startsWith('/api/weekly/')) status = 403
    return req.respond({ status, contentType: 'application/json', body: JSON.stringify({ success: status === 200, data }) })
  })
  async function waitText(text) { await page.waitForFunction(s => document.body.innerText.includes(s), {}, text) }
  async function button(label) {
    await page.$$eval('button', (buttons, label) => {
      const button = buttons.find(b => b.textContent.trim() === label && b.offsetWidth)
      if (!button) throw Error('Missing action ' + label)
      button.click()
    }, label)
  }
  await page.setViewport({ width: 1280, height: 860 })
  await page.goto(base + '/weekly')
  await waitText('本校暂未布置训练赛')
  await button('布置训练赛')
  await page.type('#weekly-contest-url', meta.url)
  await button('识别比赛')
  await waitText('各年级任选题数')
  await button('发布训练赛')
  await waitText('学生完成情况')
  assert.deepEqual(submitted.targets, targets)
  assert.equal(submitted.url, meta.url)
  assert.equal(new Date(submitted.weekStart + 'T00:00:00Z').getUTCDay(), 1)
  assert.equal(await page.$('table.standings'), null)
  await page.click('summary')
  await waitText('已达标')
  await waitText('未绑定')
  await page.click('details label.el-checkbox')
  await page.waitForFunction(() => !document.querySelector('details').innerText.includes('测试甲'))
  await page.click('a[href="/weekly/1/6"]')
  await waitText('校内榜单')
  assert.equal(await page.$$eval('.standings tbody', groups => groups.length), 2)
  assert.equal(await page.$eval('.standings tbody:last-of-type td:first-child', td => td.textContent.trim()), '')
  assert.equal(await page.$eval('.standings tbody:last-of-type', el => el.textContent.includes('00:01:23')), false)
  await page.setViewport({ width: 390, height: 844 })
  await page.click('button[aria-label="虚拟参赛"]')
  await waitText('虚拟参赛')
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  management = false
  await page.goto(base + '/weekly')
  await waitText('学生完成情况')
  assert.equal(await page.$$eval('button', buttons => buttons.some(b => b.textContent.trim() === '布置训练赛')), false)
  await page.goto(base + '/weekly/2/6')
  await waitText('无法读取这场校内训练赛')
  assert.equal(await page.$('table.standings'), null)
  assert.deepEqual(errors, [])
  console.log('Weekly training browser regression passed: publish, overview, filters, VP and practice rows, mobile and denied scope.')
} finally {
  await browser?.close()
  await new Promise(resolve => server.httpServer.close(resolve))
}
