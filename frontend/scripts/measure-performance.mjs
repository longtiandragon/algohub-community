/** Measure the production build served locally against the seeded demo API. */
import puppeteer from 'puppeteer-core'
import fs from 'node:fs'
import { performance } from 'node:perf_hooks'

const base = new URL(process.env.PERF_BASE || 'http://127.0.0.1:18541')
if (base.hostname !== '127.0.0.1') throw new Error('Use a loopback demo origin')
const cases = [
  ['login', '/login', 'input[type=email]:enabled', null],
  ['home', '/', '.bar-value', 'chenyiming'],
  ['profile', '/profile', '.profile-card .kpi:first-child dd .num', 'chenyiming'],
  ['ranking', '/ranking', '[data-test=ranking-row], [data-test=podium]', 'chenyiming'],
  ['problems', '/problems', '[data-problem]', 'chenyiming'],
  ['admin', '/admin?tab=users', '.user-cell', 'admin']
]
const executablePath = [process.env.CHROME_PATH, `${process.env.PROGRAMFILES}/Google/Chrome/Application/chrome.exe`, `${process.env['PROGRAMFILES(X86)']}/Microsoft/Edge/Application/msedge.exe`].find(p => p && fs.existsSync(p))
const browser = await puppeteer.launch({ executablePath, headless: true, args: ['--no-proxy-server'] })
const results = []
try {
  for (const [name, path, selector, username] of cases) {
    const ctx = await browser.createBrowserContext()
    const page = await ctx.newPage()
    await page.setViewport({ width: 1440, height: 900 })
    if (username) {
      await page.goto(new URL('/api/auth/email/status', base).href)
      const ok = await page.evaluate(async (user) => {
        const admin = user === 'admin'
        const res = await fetch(admin ? '/api/auth/login' : '/api/auth/demo-login', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(admin ? { username: user, password: 'admin12345' } : { username: user })
        })
        return res.ok && (await res.json()).success
      }, username)
      if (!ok) throw new Error(`Demo login failed for ${name}`)
    }
    const cdp = await page.createCDPSession()
    await cdp.send('Network.enable')
    await cdp.send('Network.emulateNetworkConditions', {offline:false, latency:50, downloadThroughput:1_000_000, uploadThroughput:500_000})
    for (let run = 1; run <= Number(process.env.PERF_RUNS || 10); run++) {
      for (const cache of ['cold', 'warm']) {
        if (cache === 'cold') await cdp.send('Network.clearBrowserCache')
        const start = performance.now()
        await page.goto(new URL(path, base).href, {waitUntil:'domcontentloaded'})
        await page.waitForSelector(selector, {visible:true,timeout:25000})
        const readyMs = Math.round(performance.now() - start)
        const metrics = await page.evaluate(() => {
          const nav = performance.getEntriesByType('navigation')[0]
          const resources = performance.getEntriesByType('resource')
          return {firstPaintMs:Math.round(performance.getEntriesByName('first-contentful-paint')[0]?.startTime ?? 0), ttfbMs:Math.round(nav.responseStart), bytes:resources.reduce((sum,r)=>sum+r.transferSize,nav.transferSize), refreshEndMs:Math.round(resources.find(r=>r.name.endsWith('/api/auth/refresh'))?.responseEnd ?? 0), jsEndMs:Math.round(Math.max(0,...resources.filter(r=>new URL(r.name).pathname.endsWith('.js')).map(r=>r.responseEnd)))}
        })
        results.push({name,run,cache,readyMs,...metrics})
        if (process.env.PERF_OUTPUT) fs.writeFileSync(process.env.PERF_OUTPUT, JSON.stringify(results))
        await page.waitForNetworkIdle({idleTime:200,timeout:6000}).catch(()=>{})
      }
    }
    console.log(name, results.filter(r=>r.name===name).length, 'samples')
    await ctx.close()
  }
} finally { await browser.close() }
