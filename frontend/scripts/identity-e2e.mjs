/** 仅操作隔离的 demo 数据库，所有邮件自动发送关闭。需先 scripts/start-demo.ps1。 */
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import puppeteer from 'puppeteer-core'
import { createServer } from 'vite'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const output = path.join(root, '.screenshots/reminders-e2e')
fs.mkdirSync(output, { recursive: true })
// 示例后端和 Vite 端口可以用环境变量换（和截图脚本的 8082 / 另一个 Vite 并存时）
process.env.ALGOHUB_API_TARGET ||= 'http://127.0.0.1:8081'
const port = Number(process.env.E2E_PORT || 5176)
const server = await createServer({ root, configFile: path.join(root, 'vite.config.ts'), server: { host: '127.0.0.1', port, strictPort: true }, logLevel: 'error' })
await server.listen()
const chrome = [process.env.CHROME_PATH, 'C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(p => p && fs.existsSync(p))
const browser = await puppeteer.launch({ executablePath: chrome, headless: true, args: ['--no-sandbox'] })
const base = `http://127.0.0.1:${port}`
const errors = []
async function login(username, password, route) {
  const context = await browser.createBrowserContext()
  const page = await context.newPage()
  page.on('pageerror', e => errors.push(String(e)))
  await page.setViewport({ width: 1440, height: 1000 })
  await page.goto(`${base}/api/auth/email/status`)
  // 普通用户没有密码登录：示例同学走只在 demo 配置下存在的 /api/auth/demo-login，管理员用密码
  assert.equal(await page.evaluate(async (username, password) => (await (await fetch(password == null ? '/api/auth/demo-login' : '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(password == null ? { username } : { username, password }) })).json()).success, username, password), true)
  await page.goto(`${base}${route}`, { waitUntil: 'networkidle0' })
  return page
}
async function api(page, path) {
  return page.evaluate(async path => (await import('/src/api/client.ts')).get(path), path)
}
async function clickText(page, text) {
  const clicked = await page.evaluate(text => {
    const element = [...document.querySelectorAll('button')].find(b => b.textContent.trim() === text && b.getClientRects().length && getComputedStyle(b).visibility !== 'hidden')
    if (!element) return false
    element.click(); return true
  }, text)
  assert.equal(clicked, true, `missing button: ${text}`)
}
async function fill(page, selector, value) {
  await page.waitForSelector(selector)
  await page.$eval(selector, (el, value) => { el.focus(); el.value = value; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); el.blur() }, String(value))
}
async function mutation(page, suffix, click) {
  const response = page.waitForResponse(r => r.url().endsWith(suffix) && r.request().method() === 'POST')
  await click()
  const data = await (await response).json()
  assert.equal(data.success, true, data.message)
  return data.data
}
try {
 const student=await login('chenyiming',null,'/settings?section=school');
 // 48cad88 起班级由同学自己填写（不再锁定），这里只确认学校与年级分组能打开、班级框可编辑
 await student.waitForSelector('[data-test="school-class"]');
 assert.equal(await student.$eval('[data-test="school-class"]', e=>e.disabled),false);
 await student.screenshot({path:output+'/identity-school.png',fullPage:true});
 await student.goto(base+'/settings?section=privacy',{waitUntil:'networkidle0'});
 assert.ok((await student.$eval('body',e=>e.innerText)).includes('站长因管理需要可以查看'));
 await student.goto(base+'/settings?section=data',{waitUntil:'networkidle0'});
 await clickText(student,'注销我的账号');
 await fill(student,'.el-message-box input','chenyiming');
 await clickText(student,'确认注销');
 await student.waitForFunction(()=>document.body.innerText.includes('发送验证码'));
 await student.screenshot({path:output+'/identity-confirm.png',fullPage:true});
 await clickText(student,'取消');
 const admin=await login('admin','admin12345','/admin?tab=members');
 // 11ff563 起成员资料在「学生与认证」里用「改资料」编辑
 await admin.waitForFunction(()=>document.body.innerText.includes('改资料'));
 await clickText(admin,'改资料');
 await admin.waitForSelector('.el-dialog input');
 await new Promise(resolve => setTimeout(resolve, 400));
 await admin.screenshot({path:output+'/identity-admin.png'});
 await admin.setViewport({width:390,height:844});
 await new Promise(resolve => setTimeout(resolve, 400));
 await admin.screenshot({path:output+'/identity-admin-mobile.png'});
 await mutation(admin,'/profile',()=>clickText(admin,'保存'));
 assert.deepEqual(errors,[]);
 console.log('PASS: school section editable; privacy disclosure; deletion identity prompt; admin profile save');
} finally { await browser.close();await server.close(); }
