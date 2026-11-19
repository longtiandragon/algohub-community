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
  let management = true, submitted = null, scheduled = null, savedDefaults = null
  const meta = { platform: 'CODEFORCES', ref: '2278', title: 'Codeforces Round 1131 (Div. 3)', category: 'Div.3',
    url: 'https://codeforces.com/contest/2278', tasks: [...'ABCDEFG'].map(index => ({ index, key: '2278' + index })) }
  const targets = { 2026: 4, 2025: 5 }
  const user = { id: 3, username: 'fixture', displayName: '测试账号', role: 'STUDENT', emailVerified: true, onboardingCompleted: true,
    memberships: [{ schoolId: 1, schoolName: '测试学校', status: 'APPROVED', enrollmentYear: 2026 }], managedCohorts: [{ schoolId: 1, enrollmentYear: 2026 }] }
  const students = [{ userId: 4, year: 2026, name: '测试甲', realName: '王明远', solved: 5, goal: 4, status: 'MET' },
    { userId: 5, year: 2025, name: '测试乙', solved: 0, goal: 5, status: 'UNBOUND' }]
  const cell = { solved: true, wrong: 2, score: 1, seconds: 83 }
  const rows = [{ userId: 4, name: '测试甲', realName: '王明远', kind: 'VIRTUAL', rank: 1, cells: { A: cell }, score: 1, penalty: 83 },
    { userId: 4, name: '测试甲', realName: '王明远', kind: 'PRACTICE', rank: null, cells: { E: cell }, score: null, penalty: null }]
  const contest = () => ({ id: 6, meta, targets, myProgress:{...students[0],userId:user.id}, weekStart: submitted.weekStart, students, status: submitted.status, audience: submitted.audience })
  await page.setRequestInterception(true)
  page.on('request', req => {
    const url = new URL(req.url()), path = url.pathname
    if (!path.startsWith('/api/')) return req.continue()
    let data = [], status = 200
    if (path === '/api/auth/refresh') data = { accessToken: 'mock', user }
    else if (path === '/api/me') data = user
    else if (path === '/api/weekly/schools') data = [{ id: 1, name: '测试学校', manage: management }]
    else if (path === '/api/weekly/1/preview') data = { meta, targets, history: submitted ? [{weekStart:submitted.weekStart,status:'PUBLISHED'}] : [], years: [2026, 2025, 2024, 2023], audience: 'ALL', lastStudentIds: [] }
    else if (path === '/api/schools/1/students/directory') {
      const roster = Array.from({length: 65}, (_, i) => ({
        userId: i + 4, name: '学生' + i, year: 2026, className: '计算机一班', studentNo: '202600' + i,
      }))
      if (url.searchParams.get('ids') === '4') Object.assign(roster[0], { name: '测试甲', realName: '王明远' })
      const page = Number(url.searchParams.get('page') || 1), size = Number(url.searchParams.get('size') || 30)
      data = {items: roster.slice((page-1)*size, page*size), total: roster.length, groups: [{year: 2026, className: '计算机一班', count: 65}]}
    }
    else if (path === '/api/weekly/1/defaults') { savedDefaults = JSON.parse(req.postData()); data = true }
    else if (path === '/api/weekly/1/overview') {
      const currentWeek = url.searchParams.get('weekStart')
      data = { contests: [], ranking: [] }
      if (submitted?.weekStart === currentWeek) {
        data.contests.push(contest())
        data.ranking.push({userId:4, year:2026, name:'测试甲', realName:'王明远', solved:5, rank:1})
      }
      if (management && scheduled?.weekStart === currentWeek) data.contests.push({ ...contest(), ...scheduled, id:7, students:[] })
    }
    else if (path === '/api/weekly/1' && req.method() === 'POST') {
      const body = JSON.parse(req.postData())
      if (body.status === 'SCHEDULED') { scheduled = body; data = 7 }
      else { submitted = body; data = 6 }
    }
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
  await waitText('该周暂无已发布的训练赛')
  const nav = await page.$$eval('nav[aria-label="主导航"] a', links => links.map(link => link.pathname))
  const accounts = nav.indexOf('/accounts')
  assert.ok(accounts >= 0)
  assert.deepEqual(nav.slice(accounts + 1, accounts + 3), ['/activity', '/weekly'])
  await page.click('a.publish-link')
  await page.waitForSelector('#weekly-contest-url')
  await page.type('#weekly-contest-url', meta.url)
  await button('识别比赛')
  await waitText(meta.title)
  await button('立即发布')
  await waitText('累计过题榜')
  await waitText('我的目标：至少 4 题')
  assert.deepEqual(submitted.targets, targets)
  assert.equal(submitted.url, meta.url)
  assert.equal(new Date(submitted.weekStart + 'T00:00:00Z').getUTCDay(), 1)
  assert.equal(await page.$('table.standings'), null)
  assert.equal(await page.$('summary'), null)
  await button('累计')
  await page.waitForSelector('.range-switch button:last-child[aria-pressed=true]')
  await button('本周')
  await page.click('button[aria-label="下一周"]')
  await waitText('该周暂无已发布的训练赛')
  await page.click('button[aria-label="选择训练周"]')
  await page.waitForSelector('.calendar .selected', {visible:true})
  assert.equal(await page.$$eval('.calendar button.selected', nodes => nodes.length), 7)
  await page.click('.calendar .today')
  await waitText('累计过题榜')
  await page.click('a.publish-link')
  await page.waitForSelector('#weekly-contest-url')
  await page.type('#weekly-contest-url', meta.url)
  await button('识别比赛')
  await waitText(meta.title)
  await waitText('所选训练周已安排这场比赛')
  assert.ok(await page.$eval('.weekly-editor .actions button', button => button.disabled))
  await page.click('button[aria-label="下一周"]')
  await waitText('可以继续布置到所选训练周')
  await waitText(submitted.weekStart)
  await page.click('input[value="SELECTED"]')
  await button('选择学生')
  await waitText('计算机一班')
  await waitText('2026000')
  await page.waitForSelector('.roster .el-loading-mask', {hidden: true})
  await page.click('.roster label input:not(:disabled)')
  await button('下一页')
  await waitText('20260030')
  await page.waitForSelector('.roster .el-loading-mask', {hidden: true})
  await page.click('.roster label input:not(:disabled)')
  await button('上一页')
  await waitText('2026000')
  assert.ok(await page.$eval('.roster input', input => input.checked))
  await button('查看已选（2）')
  assert.equal(await page.$$eval('.roster label', rows => rows.length), 2)
  await button('完成')
  await page.click('.picker-trigger')
  await waitText('选中整班')
  await button('选中整班')
  await waitText('已选 65 人')
  await button('取消')
  await button('保存为默认配置')
  await page.waitForFunction(() => !document.querySelector('.weekly-editor .actions button:disabled'))
  assert.deepEqual(savedDefaults.targets, targets)
  assert.equal(savedDefaults.audience, 'SELECTED')
  assert.equal(savedDefaults.studentIds, undefined)
  await page.$$eval('label', labels => labels.find(el => el.textContent.trim() === '定时发布').click())
  const initialTime = await page.$eval('input[type="datetime-local"]', input => input.value)
  const elapsed = Date.now() - Date.parse(initialTime + ':00+08:00')
  assert.ok(elapsed >= 0 && elapsed < 65_000)
  await page.$eval('input[type="datetime-local"]', input => {
    input.value = new Date(Date.now() + 48*3600*1000).toISOString().slice(0,16)
    input.dispatchEvent(new Event('input',{bubbles:true}))
  })
  const chosenTime = await page.$eval('input[type="datetime-local"]', input => input.value)
  for (const mode of ['立即发布','定时发布']) {
    await page.$$eval('label', (labels, text) => labels.find(el => el.textContent.trim() === text).click(), mode)
  }
  assert.equal(await page.$eval('input[type="datetime-local"]', input => input.value), chosenTime)
  await button('确认定时发布')
  await waitText('发布管理')
  assert.deepEqual(scheduled.studentIds, [4,34])
  assert.equal(scheduled.status, 'SCHEDULED')
  assert.equal(scheduled.audience, 'SELECTED')
  await page.click('button[aria-label="上一周"]')
  await page.waitForSelector('a[href^="/weekly/1/6"]')
  await page.focus('a[href^="/weekly/1/6"]')
  await page.keyboard.press('Enter')
  await waitText('我的目标：至少 4 题 · 已通过 5 题')
  await waitText('参赛成绩')
  await waitText('王明远')
  assert.equal(await page.$$eval('.standings tbody th.name', rows => rows.filter(row => row.textContent.includes('王明远')).length), 2)
  assert.equal(await page.$$eval('.standings tbody', groups => groups.length), 2)
  assert.equal(await page.$eval('.score-section:nth-of-type(2) tbody td:first-child', td => td.textContent.trim()), '')
  assert.equal(await page.$eval('.score-section:nth-of-type(2) tbody', el => el.textContent.includes('00:01:23')), false)
  await page.setViewport({ width: 390, height: 844 })
  await page.click('button[aria-label="虚拟参赛"]')
  await waitText('虚拟参赛')
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  const previousRows = rows.splice(0)
  await button('刷新榜单')
  await waitText('暂无参赛成绩')
  await waitText('暂无补题记录')
  assert.deepEqual(await page.$$eval('.score-section thead', heads => heads.map(head => head.lastElementChild.lastElementChild.textContent)), ['G','G'])
  assert.equal(await page.$$eval('.totals td', cells => cells[1].textContent.trim()), '0 / 0')
  rows.push(...previousRows)
  await page.waitForSelector('button[aria-label="虚拟参赛"]', { timeout: 25000 })
  management = false
  await page.goto(base + '/weekly')
  await waitText('累计过题榜')
  assert.equal(await page.$$eval('button', buttons => buttons.some(b => b.textContent.trim() === '布置训练赛')), false)
  await page.goto(base + '/weekly/2/6')
  await waitText('无法读取这场校内训练赛')
  assert.equal(await page.$('table.standings'), null)
  assert.deepEqual(errors, [])
  console.log('Weekly training browser regression passed: week switching, seven-day highlight, public ranking, student picker, defaults, scheduled release, VP and practice rows, mobile and denied scope.')
} finally {
  await browser?.close()
  await new Promise(resolve => server.httpServer.close(resolve))
}
