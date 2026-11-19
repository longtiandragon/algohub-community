import assert from 'node:assert/strict'
import { existsSync } from 'node:fs'
import puppeteer from 'puppeteer-core'
import { createServer } from 'vite'

const executablePath = [process.env.CHROME_PATH, 'C:/Program Files/Google/Chrome/Application/chrome.exe', '/usr/bin/chromium'].find(file => file && existsSync(file))
const server = await createServer({server: {host: '127.0.0.1', port: 0}, logLevel: 'error'})
await server.listen()
const base = 'http://127.0.0.1:' + server.httpServer.address().port
const browser = await puppeteer.launch({executablePath, headless: true})
try {
  const page = await browser.newPage()
  const school = {id: 1, schoolId: 2, schoolName: '示例学校', status: 'APPROVED', enrollmentYear: 2025}
  const user = {id: 3, username: 'example', displayName: '学生昵称', role: 'STUDENT', emailVerified: true,
    onboardingCompleted: true, profileCompletionRequired: true, realName: null, className: '一班', enrollmentYear: 2025,
    studentNo: '202501', managedSchools: [{id: 2, name: school.schoolName}], memberships: [school], privacy: {}, bindings: []}
  let submitted
  await page.setRequestInterception(true)
  page.on('request', req => {
    const path = new URL(req.url()).pathname
    if (!path.startsWith('/api/')) return req.continue()
    let data = []
    if (path === '/api/auth/refresh') data = {accessToken: 'fixture', user}
    if (path === '/api/schools') data = [{id: 2, name: school.schoolName}]
    if (path === '/api/me') data = user
    if (path === '/api/schools/memberships') data = user.memberships
    if (path === '/api/schools/join' || path === '/api/schools/memberships/1') {
      submitted = JSON.parse(req.postData())
      Object.assign(user, {realName: submitted.realName, memberships: [school]})
      data = school
    }
    if (path === '/api/auth/enrollment-years') data = [2025]
    if (path === '/api/me/complete-academic-profile' || path === '/api/me/onboarding') {
      submitted = JSON.parse(req.postData())
      Object.assign(user, {realName: submitted.realName, onboardingCompleted: true, profileCompletionRequired: false})
      data = user
    }
    return req.respond({contentType: 'application/json', body: JSON.stringify({success: true, data})})
  })
  for (const route of ['/weekly','/weekly/2/4','/weekly/2/publish','/school-admin']) {
    await page.goto(base + route)
    await page.waitForSelector('#repair-real-name')
    assert.equal(new URL(page.url()).pathname, '/complete-academic-profile')
  }
  assert.equal(await page.$eval('#repair-class', input => input.readOnly), true)
  await page.click('button[form=complete-academic-form]')
  assert.equal(await page.$eval('#repair-real-name', input => input.validity.valueMissing), true)
  await page.type('#repair-real-name', '  陈同学  ')
  await page.click('button[form=complete-academic-form]')
  await page.waitForFunction(() => location.pathname !== '/complete-academic-profile')
  assert.equal(submitted.realName, '陈同学')
  assert.equal(submitted.studentNo, '202501')
  Object.assign(user, {realName: null, managedSchools: [], memberships: [], profileCompletionRequired: true, className: ''})
  await page.goto(base + '/ranking')
  const missingClass = await page.waitForSelector('#repair-class')
  assert.equal(await page.$('#repair-real-name'), null)
  await missingClass.type('一班')
  await page.click('button[form=complete-academic-form]')
  await page.waitForFunction(() => location.pathname === '/ranking')
  assert.equal(submitted.realName, null)
  Object.assign(user, {className: '一班', onboardingCompleted: false})
  await page.goto(base + '/select-school')
  await page.waitForSelector('button.auth-button')
  await page.click('button.auth-button')
  await page.waitForSelector('#profile-name')
  assert.equal(await page.$('#profile-real-name'), null)
  await page.click('button.auth-button')
  await page.waitForFunction(() => location.pathname === '/accounts')
  assert.equal(submitted.realName, null)
  user.onboardingCompleted = false
  await page.goto(base + '/select-school')
  await page.waitForSelector('.el-select__wrapper', {visible: true})
  await page.click('.el-select__wrapper')
  await page.waitForSelector('.el-select-dropdown__item', {visible: true})
  await page.$$eval('.el-select-dropdown__item', options => options.find(o => o.textContent.trim() === '示例学校').click())
  await page.click('button.auth-button')
  await page.waitForSelector('#profile-real-name')
  await page.click('button.auth-button')
  assert.ok(await page.$eval('#profile-real-name', input => !input.checkValidity()))
  await page.type('#profile-real-name', '  林同学  ')
  await page.click('button.auth-button')
  await page.waitForFunction(() => location.pathname === '/accounts')
  assert.equal(submitted.realName, '林同学')
  assert.equal(submitted.displayName, '学生昵称')
  await page.goto(base + '/settings?section=school')
  const invite = await page.waitForSelector('input[placeholder="例如 SZPU-2026"]')
  await invite.type('FIXTURE')
  const joined = page.waitForResponse(response => response.url().endsWith('/api/schools/join'))
  await page.$$eval('button', buttons => buttons.find(b => b.textContent.trim() === '确认加入学校').click())
  await joined
  assert.equal(submitted.realName, '林同学')
  await page.waitForFunction(() => !document.querySelector('input[placeholder="例如 SZPU-2026"]'))
  const name = await page.waitForSelector('input[data-test="school-real-name"], [data-test="school-real-name"] input')
  await name.focus()
  await page.keyboard.down('Control')
  await page.keyboard.press('a')
  await page.keyboard.up('Control')
  await name.type('林小雨')
  const edited = page.waitForResponse(response => response.url().endsWith('/api/schools/memberships/1'))
  await page.$$eval('button', buttons => buttons.find(b => b.textContent.trim() === '保存').click())
  await edited
  assert.equal(submitted.realName, '林小雨')
  console.log('PASS real name onboarding, legacy repair, direct weekly/management gates, school joining and settings edits')
} finally {
  await browser.close()
  await server.close()
}
