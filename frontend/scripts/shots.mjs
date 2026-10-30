/**
 * 页面改版的截图对照：连示例后端（真实接口，不拦截、不用 mock），给每个页面按
 * 390 / 834 / 1440 三个宽度 × 浅色 / 深色拍图，同时检查横向滚动、控制台报错和失败的接口请求。
 *
 * 前置：
 *   1. 示例后端在跑：scripts/start-demo.ps1（8081 端口，示例库 algohub_demo，示例账号见 README）；
 *   2. 本机装了 Chrome（按常见路径找，也可用 CHROME_PATH 指定）；puppeteer-core 不下载浏览器。
 *
 * 用法（在 frontend 目录）：
 *   npm run shots                          全部页面
 *   npm run shots -- home ranking          只拍这几页（页面名见下面的 PAGES）
 *   npm run shots -- home --label=before   文件名前缀，默认 after；同一页改前改后放在一个文件夹里比
 *   其他选项：--widths=390,1440  --themes=dark  --fold（只拍首屏）  --strict（有问题时退出码为 1）
 *            --api=http://127.0.0.1:8081（示例后端地址）  --base=http://127.0.0.1:5174（用已开着的前端，不另起 Vite）
 *   拍开发库里自己的数据：npm run shots -- home --api=http://127.0.0.1:8080 --as=<用户名> --password=<密码>
 *   换一个示例同学（不带 --password 就走示例登录）：npm run shots -- settings-profile --as=s2026001
 *   （需要登录的页面都改用这个账号；只有管理员能用密码登录，账号要已验证登录邮箱，登录会写一条审计记录）
 *   和另一套截图逐像素对比：npm run shots -- --diff=base
 *   （每张和同名的 base-<宽度>-<主题>.png 比，报告变了多少像素、在哪一块，另存标红的 diff-<label>-*.png；
 *    变化超过 --diff-threshold=0.2（百分比）或高度不同就算问题。「外观不变」的步骤先拍一套 base 再改代码）
 *   查文字对比度：npm run shots -- --contrast（普通文字低于 4.5:1、大字低于 3:1 的算问题，列出最差的几处）
 *   记下接口请求：npm run shots -- home --requests（每张图从打开到截图之间请求了哪些接口、各几次、第几秒）
 *   看轮询：npm run shots -- home --requests --hold=40 --widths=1440 --themes=light（截图前多停 40 秒）
 *
 * 截图在 frontend/.screenshots/redesign/<页面>/<label>-<宽度>-<主题>.png（已在 .gitignore 里），
 * 汇总在 frontend/.screenshots/redesign/report-<label>.md。
 * 脚本自己起一个 Vite（5175 端口，接口代理到 --api），不影响平时开着的 5174。
 * 同一个账号的页面依次打开：每次整页加载都会用 Cookie 换一次访问令牌，刷新令牌随之轮换，不能并行。
 */
import puppeteer from 'puppeteer-core'
import { createServer } from 'vite'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const OUT = path.join(ROOT, '.screenshots', 'redesign')

/** 页面名、路径、用哪个示例账号登录（null 表示不登录）。 */
const PAGES = [
  { name: 'login', path: '/login', user: null },
  { name: 'forgot-password', path: '/forgot-password', user: null },
  { name: 'oauth-callback', path: '/oauth/github/callback?error=access_denied', user: null },
  // GitHub 已验证邮箱对应已有账号时的关联页（邮箱验证码 / 原账号密码）
  { name: 'github-link', path: '/oauth/github/link', user: null },
  { name: 'github-link-password', path: '/oauth/github/link?mode=password', user: null },
  { name: 'verify-email', path: '/verify-email', user: 'unverified' },
  { name: 'select-school', path: '/select-school', user: 'noschool' },
  { name: 'select-school-pending', path: '/select-school', user: 'pending' },
  { name: 'home', path: '/', user: 'chenyiming' },
  { name: 'reminders', path: '/reminders', user: 'chenyiming' },
  { name: 'announcements', path: '/announcements', user: 'chenyiming' },
  { name: 'admin-reminders', path: '/admin?tab=reminders', user: 'admin' },
  { name: 'admin-years', path: '/admin?tab=years', user: 'admin' },
  { name: 'home-new', path: '/', user: 'newbie' },
  { name: 'ranking', path: '/ranking', user: 'chenyiming' },
  { name: 'profile', path: '/profile', user: 'chenyiming' },
  // 个人档案的「做题记录」标签（第 24 步起：电脑表格、手机按天分组）
  { name: 'profile-records', path: '/profile?tab=records', user: 'chenyiming' },
  // 分布、比赛与 Rating、报告与导出三个标签（第 26 步起）
  { name: 'profile-distribution', path: '/profile?tab=distribution', user: 'chenyiming' },
  { name: 'profile-rating', path: '/profile?tab=rating', user: 'chenyiming' },
  { name: 'profile-reports', path: '/profile?tab=reports', user: 'chenyiming' },
  // 同学档案（从排行、动态点名字进来）：看得到的同学，和设成「仅自己」的同学
  { name: 'public-profile', path: '/users/3', user: 'chenyiming' },
  { name: 'public-profile-hidden', path: '/users/63', user: 'chenyiming' },
  { name: 'accounts', path: '/accounts', user: 'chenyiming' },
  // 平台账号的二级页面（第 19 步起；手机上从账号页进来，电脑上同样的内容在账号页里展开）
  { name: 'accounts-sync-log', path: '/accounts/sync-log', user: 'chenyiming' },
  // 洛谷浏览器同步（平台账号里洛谷卡片进来）
  { name: 'luogu-sync', path: '/luogu-sync', user: 'chenyiming' },
  { name: 'activity', path: '/activity', user: 'chenyiming' },
  { name: 'contests', path: '/contests', user: 'chenyiming' },
  { name: 'settings', path: '/settings', user: 'chenyiming' },
  { name: 'settings-profile', path: '/settings?section=profile', user: 'chenyiming' },
  // 设置的分组（第 29 步起：电脑左边导航切换，手机是列表里点进去的子页）
  { name: 'settings-privacy', path: '/settings?section=privacy', user: 'chenyiming' },
  { name: 'settings-devices', path: '/settings?section=devices', user: 'chenyiming' },
  { name: 'settings-school', path: '/settings?section=school', user: 'chenyiming' },
  { name: 'settings-appearance', path: '/settings?section=appearance', user: 'chenyiming' },
  { name: 'settings-login-email', path: '/settings?section=login-email', user: 'chenyiming' },
  { name: 'settings-oauth', path: '/settings?section=oauth', user: 'chenyiming' },
  { name: 'settings-events', path: '/settings?section=events', user: 'chenyiming' },
  { name: 'settings-data', path: '/settings?section=data', user: 'chenyiming' },
  // 「我的」（第 9 步起）：管理员多一组「管理」
  { name: 'me', path: '/me', user: 'chenyiming' },
  { name: 'me-admin', path: '/me', user: 'admin' },
  { name: 'admin', path: '/admin', user: 'admin' },
  // 管理端扩充（docs/2026-09-30-管理端扩充.md）：数据概览、用户管理、管理员与权限、操作日志
  { name: 'admin-dashboard', path: '/admin?tab=dashboard', user: 'admin' },
  { name: 'admin-users', path: '/admin?tab=users', user: 'admin' },
  { name: 'admin-admins', path: '/admin?tab=admins', user: 'admin' },
  { name: 'admin-audit', path: '/admin?tab=audit', user: 'admin' },
  // 管理后台的其他标签（第 31 步起）
  { name: 'admin-members', path: '/admin?tab=members', user: 'admin' },
  { name: 'admin-appeals', path: '/admin?tab=appeals', user: 'admin' },
  { name: 'admin-tags', path: '/admin?tab=tags', user: 'admin' },
  { name: 'admin-groups', path: '/admin?tab=problem-groups', user: 'admin' },
  { name: 'admin-nav', path: '/admin?tab=nav', user: 'admin' },
  // 练习页：导航里暂不显示，页面照常可用
  { name: 'problems', path: '/problems', user: 'chenyiming' },
  { name: 'problem-lists', path: '/problem-lists', user: 'chenyiming' },
  { name: 'problem-list-detail', path: '/problem-lists/1', user: 'chenyiming' },
  { name: 'skill-map', path: '/skill-map', user: 'chenyiming' },
  { name: 'activities', path: '/activities', user: 'chenyiming' },
  { name: 'bookmarks', path: '/bookmarks', user: 'chenyiming' },
  { name: 'ai-archive', path: '/ai-archive', user: 'chenyiming' },
  // 组件样板页（只在开发环境有，第 5 步起）
  { name: 'dev-ui', path: '/dev/ui', user: null }
]

/**
 * 管理员用密码登录（scripts/start-demo.ps1 写入的示例数据）。普通用户没有密码登录，
 * 示例学生走只在 demo 配置下存在的 /api/auth/demo-login（DemoLoginController），按用户名直接登录。
 */
const PASSWORDS = { admin: 'admin12345' }

/** 各宽度的首屏高度：iPhone 14、iPad Air 竖放、常见笔记本。 */
const HEIGHTS = { 390: 844, 834: 1112, 1440: 900 }

const names = []
const options = {}
for (const arg of process.argv.slice(2)) {
  if (arg.startsWith('--')) {
    const [key, value] = arg.slice(2).split('=')
    options[key] = value ?? true
  } else {
    names.push(...arg.split(','))
  }
}

const unknown = names.filter((name) => !PAGES.some((page) => page.name === name))
if (unknown.length) {
  console.error(`没有这些页面：${unknown.join('、')}\n可选：${PAGES.map((page) => page.name).join(' ')}`)
  process.exit(1)
}
const pages = names.length ? PAGES.filter((page) => names.includes(page.name)) : PAGES
const widths = String(options.widths || '390,834,1440').split(',').map(Number)
const themes = String(options.themes || 'light,dark').split(',')
const label = String(options.label || 'after')
const api = String(options.api || 'http://127.0.0.1:8081').replace(/\/$/, '')
const fold = Boolean(options.fold)
const override = options.as ? { user: String(options.as), password: options.password == null ? null : String(options.password) } : null
const diffLabel = options.diff ? String(options.diff) : null
const diffThreshold = Number(options['diff-threshold'] ?? 0.2)
const checkContrast = Boolean(options.contrast)
const recordRequests = Boolean(options.requests)
const holdSeconds = Number(options.hold ?? 0)

const CHROME = [
  process.env.CHROME_PATH,
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  '/usr/bin/google-chrome',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
].filter(Boolean).find((candidate) => fs.existsSync(candidate))
if (!CHROME) {
  console.error('没找到 Chrome，可用 CHROME_PATH 指定可执行文件路径')
  process.exit(1)
}

try {
  const res = await fetch(`${api}/api/auth/email/status`)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
} catch (e) {
  console.error(`示例后端没有响应（${api}）：先运行 scripts/start-demo.ps1。${e.message}`)
  process.exit(1)
}

let server
let base = options.base ? String(options.base).replace(/\/$/, '') : null
if (!base) {
  process.env.ALGOHUB_API_TARGET = api
  const port = Number(options.port || 5175)
  server = await createServer({
    root: ROOT,
    configFile: path.join(ROOT, 'vite.config.ts'),
    server: { host: '127.0.0.1', port, strictPort: true },
    logLevel: 'error',
    clearScreen: false
  })
  await server.listen()
  base = `http://127.0.0.1:${port}`
}

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: true,
  args: ['--no-sandbox', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1']
})

async function shutdown(code) {
  await browser.close().catch(() => undefined)
  await server?.close().catch(() => undefined)
  process.exit(code)
}
process.on('SIGINT', () => shutdown(130))

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/** 每个示例账号一个独立的浏览器上下文（Cookie 与本地存储互不影响），用接口真实登录一次。 */
const contexts = new Map()
async function contextFor(user) {
  const key = user ?? '(guest)'
  if (contexts.has(key)) return contexts.get(key)
  const context = await browser.createBrowserContext()
  if (user) {
    const page = await context.newPage()
    await page.goto(`${base}/api/auth/email/status`)
    const password = override?.password ?? PASSWORDS[user] ?? null
    const result = await page.evaluate(async (username, password) => {
      const res = await fetch(password == null ? '/api/auth/demo-login' : '/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(password == null ? { username } : { username, password })
      })
      const body = await res.json().catch(() => null)
      return { ok: res.ok && Boolean(body?.success), message: body?.message ?? `HTTP ${res.status}` }
    }, user, password)
    await page.close()
    if (!result.ok) throw new Error(`示例账号 ${user} 登录失败：${result.message}`)
  }
  contexts.set(key, context)
  return context
}

/** 页面比视口宽时，找出最先超出的元素（跳过被横向滚动容器裁掉的部分）。 */
function findOverflow() {
  const doc = document.documentElement
  const limit = doc.clientWidth + 1
  const extra = doc.scrollWidth - doc.clientWidth
  if (extra <= 1) return null
  const describe = (el) => {
    const classes = [...el.classList].slice(0, 2).map((c) => `.${c}`).join('')
    return `${el.tagName.toLowerCase()}${el.id ? `#${el.id}` : ''}${classes}`
  }
  const clipped = (el) => {
    for (let node = el.parentElement; node && node !== document.body; node = node.parentElement) {
      const style = getComputedStyle(node)
      if (['auto', 'scroll', 'hidden', 'clip'].includes(style.overflowX) && node.getBoundingClientRect().right <= limit) {
        return true
      }
    }
    return false
  }
  const culprits = []
  for (const el of document.body.querySelectorAll('*')) {
    const rect = el.getBoundingClientRect()
    if (rect.width === 0 || rect.right <= limit) continue
    const parent = el.parentElement
    if (parent && parent !== document.body && parent.getBoundingClientRect().right > limit) continue
    if (clipped(el)) continue
    culprits.push(`${describe(el)}（右边到 ${Math.round(rect.right)}）`)
    if (culprits.length >= 3) break
  }
  return { extra, culprits }
}

/**
 * 找对比度不够的文字：普通文字低于 4.5:1、大字（24px 以上，或 18.66px 以上且加粗）低于 3:1。
 * 背景从元素往上逐层叠加半透明底色；遇到背景图或渐变就算不出来，跳过。禁用的控件按规范不要求，也跳过。
 */
function findLowContrast() {
  const parse = (value) => {
    let m = value.match(/^rgba?\(([^)]+)\)/)
    if (m) {
      const [r, g, b, a = 1] = m[1].split(/[\s,/]+/).filter(Boolean).map(Number)
      return { r, g, b, a }
    }
    m = value.match(/^color\(srgb ([^)]+)\)/)
    if (m) {
      const [r, g, b, a = 1] = m[1].split(/[\s/]+/).filter(Boolean).map(Number)
      return { r: r * 255, g: g * 255, b: b * 255, a }
    }
    return null
  }
  const over = (top, bottom) => ({
    r: top.r * top.a + bottom.r * (1 - top.a),
    g: top.g * top.a + bottom.g * (1 - top.a),
    b: top.b * top.a + bottom.b * (1 - top.a),
    a: 1
  })
  const luminance = ({ r, g, b }) => {
    const channel = (v) => {
      const s = v / 255
      return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
    }
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
  }
  const background = (el) => {
    const layers = []
    for (let node = el; node; node = node.parentElement) {
      const style = getComputedStyle(node)
      if (style.backgroundImage !== 'none') return null
      const color = parse(style.backgroundColor)
      if (color && color.a > 0) {
        layers.push(color)
        if (color.a >= 1) break
      }
    }
    return layers.reduceRight((acc, layer) => over(layer, acc), { r: 255, g: 255, b: 255, a: 1 })
  }
  const describe = (el) => {
    const classes = [...el.classList].slice(0, 2).map((c) => `.${c}`).join('')
    return `${el.tagName.toLowerCase()}${classes}`
  }
  const hex = ({ r, g, b }) => `#${[r, g, b].map((v) => Math.round(v).toString(16).padStart(2, '0')).join('')}`
  const failures = []
  let checked = 0
  for (const el of document.body.querySelectorAll('*')) {
    const text = [...el.childNodes].filter((n) => n.nodeType === Node.TEXT_NODE).map((n) => n.textContent.trim()).join(' ').trim()
    if (!text) continue
    const rect = el.getBoundingClientRect()
    if (rect.width === 0 || rect.height === 0) continue
    // 只给读屏的文字（sr-only：1px 大小、裁掉）看不见，不算
    if (rect.width <= 1 && rect.height <= 1) continue
    if (el.closest('[disabled], .is-disabled, [aria-disabled="true"]')) continue
    const style = getComputedStyle(el)
    if (style.visibility !== 'visible') continue
    let opacity = 1
    for (let node = el; node; node = node.parentElement) opacity *= Number(getComputedStyle(node).opacity)
    if (opacity === 0) continue
    const fg = parse(style.color)
    const bg = background(el)
    if (!fg || !bg) continue
    checked++
    const shown = over({ ...fg, a: fg.a * opacity }, bg)
    const [l1, l2] = [luminance(shown), luminance(bg)].sort((a, b) => b - a)
    const ratio = (l1 + 0.05) / (l2 + 0.05)
    const size = parseFloat(style.fontSize)
    const large = size >= 24 || (size >= 18.66 && Number(style.fontWeight) >= 700)
    const need = large ? 3 : 4.5
    if (ratio < need) {
      failures.push({ text: text.slice(0, 24), ratio: Math.round(ratio * 100) / 100, need, fg: hex(shown), bg: hex(bg), where: describe(el) })
    }
  }
  failures.sort((a, b) => a.ratio - b.ratio)
  return { checked, count: failures.length, worst: failures.slice(0, 6) }
}

/**
 * 在浏览器里逐像素比较两张截图（不用额外依赖）：三个通道差值之和超过 48 算变了。
 * 返回变化比例（占重叠区域的百分比）、变化范围和标红的对比图（淡化的新图上把变了的像素涂红）。
 */
function comparePixels(baseB64, nextB64) {
  const load = async (b64) => {
    const img = new Image()
    img.src = `data:image/png;base64,${b64}`
    await img.decode()
    return img
  }
  return Promise.all([load(baseB64), load(nextB64)]).then(([base, next]) => {
    const width = Math.min(base.width, next.width)
    const height = Math.min(base.height, next.height)
    const read = (img) => {
      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      const ctx = canvas.getContext('2d', { willReadFrequently: true })
      ctx.drawImage(img, 0, 0)
      return ctx.getImageData(0, 0, width, height).data
    }
    const a = read(base)
    const b = read(next)
    const out = document.createElement('canvas')
    out.width = next.width
    out.height = next.height
    const ctx = out.getContext('2d')
    ctx.globalAlpha = 0.25
    ctx.drawImage(next, 0, 0)
    ctx.globalAlpha = 1
    const marks = ctx.getImageData(0, 0, width, height)
    let changed = 0
    let [minX, minY, maxX, maxY] = [width, height, -1, -1]
    for (let i = 0, p = 0; i < a.length; i += 4, p++) {
      if (Math.abs(a[i] - b[i]) + Math.abs(a[i + 1] - b[i + 1]) + Math.abs(a[i + 2] - b[i + 2]) <= 48) continue
      changed++
      const x = p % width
      const y = (p - x) / width
      if (x < minX) minX = x
      if (x > maxX) maxX = x
      if (y < minY) minY = y
      if (y > maxY) maxY = y
      marks.data.set([230, 30, 30, 255], i)
    }
    const sameSize = base.width === next.width && base.height === next.height
    if (!changed && sameSize) return { ratio: 0, heights: [base.height, next.height] }
    ctx.putImageData(marks, 0, 0)
    return {
      ratio: (changed / (width * height)) * 100,
      box: changed ? [minX, minY, maxX, maxY] : null,
      heights: [base.height, next.height],
      image: out.toDataURL('image/png')
    }
  })
}

let diffPage = null
async function diffAgainst(file, spec, width, theme) {
  const baseFile = path.join(OUT, spec.name, `${diffLabel}-${width}-${theme}.png`)
  if (!fs.existsSync(baseFile)) return { missing: true }
  diffPage ??= await browser.newPage()
  const result = await diffPage.evaluate(comparePixels, fs.readFileSync(baseFile).toString('base64'),
    fs.readFileSync(file).toString('base64'))
  const diffFile = path.join(OUT, spec.name, `diff-${label}-${width}-${theme}.png`)
  if (result.image) {
    fs.writeFileSync(diffFile, Buffer.from(result.image.split(',')[1], 'base64'))
  } else if (fs.existsSync(diffFile)) {
    fs.rmSync(diffFile)
  }
  const flagged = result.ratio > diffThreshold || result.heights[0] !== result.heights[1]
  return { ratio: result.ratio, box: result.box ?? null, heights: result.heights, flagged, file: result.image ? diffFile : null }
}

function describeDiff(diff) {
  if (!diff) return null
  if (diff.missing) return `没有 ${diffLabel} 截图可比`
  if (diff.ratio === 0 && !diff.flagged) return null
  const height = diff.heights[0] !== diff.heights[1] ? `，高度 ${diff.heights[0]}→${diff.heights[1]}` : ''
  const box = diff.box ? `，范围 x${diff.box[0]}–${diff.box[2]} y${diff.box[1]}–${diff.box[3]}` : ''
  return `和 ${diffLabel} 比变了 ${diff.ratio.toFixed(2)}%${height}${box}`
}

async function shoot(spec, width, theme) {
  const context = await contextFor(spec.user && override ? override.user : spec.user)
  const page = await context.newPage()
  const consoleErrors = []
  const apiErrors = []
  const requests = []
  const openedAt = Date.now()
  if (recordRequests) {
    page.on('request', (req) => {
      const url = new URL(req.url())
      if (url.pathname.startsWith('/api/')) requests.push({ key: `${req.method()} ${url.pathname.slice(4)}`, at: Date.now() - openedAt })
    })
  }
  page.on('console', (msg) => {
    if (msg.type() === 'error' && !msg.text().startsWith('Failed to load resource')) consoleErrors.push(msg.text())
  })
  page.on('pageerror', (err) => consoleErrors.push(String(err?.message ?? err)))
  page.on('response', (res) => {
    const url = new URL(res.url())
    if (!url.pathname.startsWith('/api/') || res.status() < 400) return
    // 没登录的页面启动时用 Cookie 换令牌，401 是正常结果
    if (!spec.user && res.status() === 401 && url.pathname === '/api/auth/refresh') return
    apiErrors.push(`${res.status()} ${url.pathname}`)
  })
  page.on('requestfailed', (req) => {
    const url = new URL(req.url())
    if (url.pathname.startsWith('/api/')) apiErrors.push(`${req.failure()?.errorText ?? '失败'} ${url.pathname}`)
  })
  const mobile = width < 768
  await page.setViewport({ width, height: HEIGHTS[width] ?? 900, deviceScaleFactor: 1, isMobile: mobile, hasTouch: mobile })
  await page.emulateMediaFeatures([
    { name: 'prefers-color-scheme', value: theme },
    { name: 'prefers-reduced-motion', value: 'reduce' }
  ])
  await page.evaluateOnNewDocument((value) => {
    try {
      localStorage.setItem('algohub-theme', value)
    } catch {
      // 拿不到存储时按系统偏好（上面已模拟）
    }
  }, theme)
  let navigationError = null
  try {
    await page.goto(`${base}${spec.path}`, { waitUntil: 'networkidle2', timeout: 45000 })
  } catch (e) {
    navigationError = e.message.split('\n')[0]
  }
  // 图表动画和按块加载的数据都要一点时间
  await sleep(1200)
  if (holdSeconds > 0) await sleep(holdSeconds * 1000)
  const overflow = await page.evaluate(findOverflow).catch(() => null)
  const contrast = checkContrast ? await page.evaluate(findLowContrast).catch(() => null) : null
  const dir = path.join(OUT, spec.name)
  fs.mkdirSync(dir, { recursive: true })
  const file = path.join(dir, `${label}-${width}-${theme}.png`)
  await page.screenshot({ path: file, fullPage: !fold })
  const expected = spec.path.split('?')[0]
  const landed = new URL(page.url()).pathname
  await page.close()
  return {
    page: spec.name, width, theme, file,
    redirect: landed !== expected ? landed : null,
    navigationError, overflow, contrast, consoleErrors: [...new Set(consoleErrors)], apiErrors: [...new Set(apiErrors)],
    requests: recordRequests ? summarizeRequests(requests) : null,
    diff: diffLabel ? await diffAgainst(file, spec, width, theme) : null
  }
}

/** 同一接口合并成一行：「GET /accounts ×2（0.4s、30.6s）」，按第一次请求的先后排 */
function summarizeRequests(requests) {
  const byKey = new Map()
  for (const request of requests) {
    if (!byKey.has(request.key)) byKey.set(request.key, [])
    byKey.get(request.key).push(request.at)
  }
  return [...byKey].map(([key, times]) => `${key} ×${times.length}（${times.map((ms) => `${(ms / 1000).toFixed(1)}s`).join('、')}）`)
}

const results = []
const started = Date.now()
let toolFailed = false
try {
  for (const spec of pages) {
    for (const theme of themes) {
      for (const width of widths) {
        const result = await shoot(spec, width, theme)
        results.push(result)
        const issues = [
          result.redirect && `跳到 ${result.redirect}`,
          result.navigationError && `加载：${result.navigationError}`,
          result.overflow && `横向多出 ${result.overflow.extra}px`,
          result.consoleErrors.length && `控制台报错 ${result.consoleErrors.length} 条`,
          result.apiErrors.length && `接口失败 ${result.apiErrors.join('、')}`,
          result.contrast?.count && `对比度不够 ${result.contrast.count} 处（最低 ${result.contrast.worst[0].ratio}）`,
          result.diff?.flagged && describeDiff(result.diff)
        ].filter(Boolean)
        const note = !result.diff?.flagged && describeDiff(result.diff)
        console.log(`${spec.name.padEnd(22)} ${String(width).padStart(4)} ${theme.padEnd(5)} ${issues.join('；') || 'ok'}${note ? `（${note}）` : ''}`)
        for (const line of result.requests ?? []) console.log(`${' '.repeat(34)}${line}`)
      }
    }
  }
} catch (e) {
  toolFailed = true
  console.error(`截图中断：${e.message}`)
}

const problems = results.filter((r) => r.redirect || r.navigationError || r.overflow || r.consoleErrors.length
  || r.apiErrors.length || r.contrast?.count || r.diff?.flagged)
const changed = results.filter((r) => r.diff && !r.diff.missing && (r.diff.ratio > 0 || r.diff.flagged))
  .sort((x, y) => y.diff.ratio - x.diff.ratio)
const lines = [
  `# 截图汇总（${label}）`,
  '',
  `${new Date().toLocaleString('zh-CN', { hour12: false })}，${results.length} 张，示例后端 ${api}，${fold ? '首屏' : '整页'}。`,
  '',
  `页面：${names.length ? pages.map((page) => page.name).join('、') : `全部 ${pages.length} 个`}；宽度 ${widths.join(' / ')}；主题 ${themes.join(' / ')}。`,
  '',
  problems.length ? '| 页面 | 宽度 | 主题 | 问题 |' : `没有发现横向滚动、控制台报错或失败的接口${diffLabel ? `，和 ${diffLabel} 相比也没有超过 ${diffThreshold}% 的变化` : ''}。`,
  ...(problems.length ? ['|---|---|---|---|'] : []),
  ...problems.map((r) => {
    const detail = [
      r.redirect && `跳到 \`${r.redirect}\``,
      r.navigationError && `加载：${r.navigationError}`,
      r.overflow && `横向多出 ${r.overflow.extra}px：${r.overflow.culprits.join('，') || '没找到具体元素'}`,
      ...r.consoleErrors.map((text) => `控制台：${text.replace(/\|/g, '\\|').slice(0, 160)}`),
      ...r.apiErrors.map((text) => `接口：${text}`),
      ...(r.contrast?.worst ?? []).map((w) => `对比度 ${w.ratio}（要 ${w.need}）：「${w.text.replace(/\|/g, '\\|')}」${w.fg} / ${w.bg} \`${w.where}\``),
      r.diff?.flagged && describeDiff(r.diff)
    ].filter(Boolean)
    return `| ${r.page} | ${r.width} | ${r.theme} | ${detail.join('<br>')} |`
  }),
  ...(diffLabel ? [
    '',
    `## 和 ${diffLabel} 逐像素对比`,
    '',
    changed.length
      ? `有变化的 ${changed.length} 张（超过 ${diffThreshold}% 或高度不同的算问题，标红的对比图是 \`diff-${label}-*.png\`）：`
      : '所有截图和对比的那一套完全一致。',
    ...changed.map((r) => `- ${r.diff.flagged ? '**' : ''}${r.page} ${r.width} ${r.theme}${r.diff.flagged ? '**' : ''}：${describeDiff(r.diff)}`)
  ] : []),
  ...(recordRequests ? [
    '',
    `## 接口请求（从打开页面到截图${holdSeconds > 0 ? `，截图前多停 ${holdSeconds} 秒` : ''}）`,
    '',
    ...results.flatMap((r) => [`- ${r.page} ${r.width} ${r.theme}：`, ...r.requests.map((line) => `  - ${line}`)])
  ] : [])
]
fs.mkdirSync(OUT, { recursive: true })
const report = path.join(OUT, `report-${label}.md`)
fs.writeFileSync(report, `${lines.join('\n')}\n`)
console.log(`\n${results.length} 张，用时 ${Math.round((Date.now() - started) / 1000)} 秒，有问题的 ${problems.length} 张 → ${report}`)

await shutdown(toolFailed || (options.strict && problems.length) ? 1 : 0)
