// Uses only the disposable demo service; never point this script at production.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import puppeteer from 'puppeteer-core'
import { createServer } from 'vite'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const chrome = [process.env.CHROME_PATH, 'C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe'].filter(Boolean).find(p => fs.existsSync(p))
assert(chrome, 'Chrome is required')
process.env.ALGOHUB_API_TARGET = 'http://127.0.0.1:8081'
const server = await createServer({ root, server: { host: '127.0.0.1', port: 5176, strictPort: true }, logLevel: 'error' })
await server.listen()
const browser = await puppeteer.launch({ executablePath: chrome, headless: true, args: ['--no-sandbox', '--disable-gpu'] })
const base = 'http://127.0.0.1:5176'
try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1440, height: 1000 })
  await page.goto(`${base}/api/auth/email/status`)
  assert.equal(await page.evaluate(async () => (await (await fetch('/api/auth/demo-login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'chenyiming' })
  })).json()).success), true)
  const feedResponse = () => {
    const pending = page.waitForResponse(r => r.url().includes('/api/activity/feed?') && r.ok())
    pending.catch(() => {})
    return pending
  }
  let response = feedResponse()
  await page.goto(`${base}/activity`)
  assert.equal(new URL((await response).url()).searchParams.get('scope'), 'PUBLIC')
  console.log('Public feed loaded')
  await page.waitForSelector('[aria-label="动态范围"] button')
  response = feedResponse()
  await page.evaluate(() => [...document.querySelectorAll('[aria-label="动态范围"] button')].find(el => el.textContent === '校内动态').click())
  assert.equal(new URL((await response).url()).searchParams.get('scope'), 'SCHOOL')
  console.log('School feed loaded')
  await page.click('[aria-label="筛选年级（多选）"]')
  await page.waitForSelector('.el-select-dropdown__item')
  for (const year of ['2026 级', '2025 级']) {
    response = feedResponse()
    await page.evaluate(label => [...document.querySelectorAll('.el-select-dropdown__item')].find(el => el.textContent.trim() === label).click(), year)
    await response
  }
  assert.equal(await page.evaluate(() => document.querySelectorAll('.el-select-dropdown__item.is-selected').length), 2)
  await page.keyboard.press('Escape')
  await page.click('h1')
  const privacyResponse = page.waitForResponse(r => r.url().endsWith('/api/me/privacy') && r.request().method() === 'POST')
  privacyResponse.catch(() => {})
  await page.click('.el-switch')
  assert.equal((await (await privacyResponse).json()).data.publicActivity, false)
  await page.reload()
  await page.waitForSelector('.el-switch:not(.is-checked)')
  const restoreResponse = page.waitForResponse(r => r.url().endsWith('/api/me/privacy') && r.request().method() === 'POST')
  restoreResponse.catch(() => {})
  await page.click('.el-switch')
  assert.equal((await (await restoreResponse).json()).data.publicActivity, true)
  await page.goto(`${base}/`)
  await page.waitForSelector('[data-test="home-platform-accounts"] .sync-dot')
  const resourcesBeforeAccounts = await page.evaluate(() => document.querySelector('[data-test="home-resources"]').getBoundingClientRect().top < document.querySelector('[data-test="home-platform-accounts"]').getBoundingClientRect().top)
  assert(resourcesBeforeAccounts)
  await page.hover('.sync-dot')
  await page.waitForFunction(() => [...document.querySelectorAll('[role="tooltip"]')].some(el => /同步正常|同步异常/.test(el.textContent)))
  console.log('PASS: public default, school switch, multi-grade selection, privacy save/reload, resource order, sync tooltip')
} catch (error) {
  console.error(error)
  process.exitCode = 1
} finally {
  await browser.close()
  await server.close()
}
