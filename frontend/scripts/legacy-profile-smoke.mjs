// Disposable demo database only: prepare chenyiming with missing class, year 2024 and studentNo 240001.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import puppeteer from 'puppeteer-core'
import { createServer } from 'vite'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const chrome = ['C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe'].find(fs.existsSync)
process.env.ALGOHUB_API_TARGET = 'http://127.0.0.1:8081'
const server = await createServer({ root, server: { host: '127.0.0.1', port: 5176, strictPort: true }, logLevel: 'error' })
await server.listen()
const browser = await puppeteer.launch({ executablePath: chrome, headless: true, args: ['--no-sandbox', '--disable-gpu'] })
const base = 'http://127.0.0.1:5176'
try {
  const page = await browser.newPage()
  await page.goto(`${base}/api/auth/email/status`)
  await page.evaluate(async () => fetch('/api/auth/demo-login', { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({username:'chenyiming'}) }))
  await page.goto(`${base}/ranking`)
  await page.waitForSelector('#repair-class')
  assert.equal(new URL(page.url()).pathname, '/complete-academic-profile')
  assert.deepEqual(await page.evaluate(() => [document.querySelector('#repair-year').value, document.querySelector('#repair-year').disabled, document.querySelector('#repair-student').value, document.querySelector('#repair-student').readOnly]), ['2024', true, '240001', true])
  for (const width of [390,1440]) {
    await page.setViewport({width,height:1000})
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    await page.screenshot({path:path.join(root,`.screenshots/legacy-profile-${width}.png`),fullPage:true})
  }
  await page.type('#repair-class','计应1班')
  const saved = page.waitForResponse(r => r.url().endsWith('/api/me/complete-academic-profile') && r.request().method()==='POST')
  await page.click('button[form="complete-academic-form"]')
  assert.equal((await (await saved).json()).data.profileCompletionRequired,false)
  await page.waitForSelector('aside[role="status"]')
  await page.goto(`${base}/settings?section=profile`)
  const input = await page.waitForSelector('[data-test="section-profile"] input[maxlength="32"]')
  await input.click({clickCount:3})
  await input.type('自选昵称')
  await page.evaluate(() => [...document.querySelectorAll('button')].find(el => el.textContent.trim()==='保存').click())
  await page.waitForFunction(() => !document.querySelector('aside[role="status"]'))
  await page.reload()
  await page.waitForSelector('[data-test="section-profile"]')
  assert.equal(await page.$('aside[role="status"]'),null)
  console.log('PASS: legacy redirect, readonly prefill, desktop/mobile layout, repair, nickname notice clears and stays cleared')
} finally { await browser.close(); await server.close() }
