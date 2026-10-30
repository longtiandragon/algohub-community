// Real cookies and actual refresh rotation. Only use an explicitly supplied test/admin account.
import fs from 'node:fs'
import assert from 'node:assert/strict'
import puppeteer from 'puppeteer-core'
const credentialsFile=process.env.ALGOHUB_TEST_CREDENTIAL_FILE
if(!credentialsFile)throw Error('Set ALGOHUB_TEST_CREDENTIAL_FILE to a private env file outside the repository')
const env=Object.fromEntries(fs.readFileSync(credentialsFile,'utf8').split(/\r?\n/).filter(l=>l&&!l.startsWith('#')).map(l=>{const n=l.indexOf('=');return [l.slice(0,n),l.slice(n+1)]}))
const origin=process.env.ALGOHUB_ADMIN_URL||'https://admin.algohub.top'
const browser=await puppeteer.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'})
let page,cleanupCookie,ua
try {
 page=await browser.newPage();await page.goto(origin,{waitUntil:'networkidle0'})
 await page.type('#login-username',env.ADMIN_USERNAME);await page.type('#login-password',env.ADMIN_PASSWORD);await page.click('button[type=submit]');await page.waitForFunction(()=>location.pathname==='/admin')
 ua=await page.evaluate(()=>navigator.userAgent)
 const cookie=async()=> (await page.cookies(origin+'/api/auth/refresh')).find(c=>c.name==='algohub_refresh').value
 async function discardedRefresh(value){
  const res=await fetch(origin+'/api/auth/refresh',{method:'POST',headers:{'Content-Type':'application/json',Cookie:'algohub_refresh='+value,'User-Agent':ua},body:'{}'})
  assert.equal(res.status,200);const match=/algohub_refresh=([^;]+)/.exec(res.headers.get('set-cookie')||'');assert.ok(match)
  cleanupCookie=match[1];await res.text();return cleanupCookie // deliberately do not apply Set-Cookie to the browser
 }
 for(let i=0;i<6;i++){await page.reload({waitUntil:'networkidle0'});assert.equal(new URL(page.url()).pathname,'/admin')}
 console.log('PASS real Cookie: six consecutive reloads')
 await discardedRefresh(await cookie());await page.reload({waitUntil:'networkidle0'});assert.equal(new URL(page.url()).pathname,'/admin')
 console.log('PASS response loss: old Cookie recovers login after server rotation')
 const old=await cookie();const next=await discardedRefresh(old);await discardedRefresh(next)
 await page.reload({waitUntil:'networkidle0'});assert.equal(new URL(page.url()).pathname,'/admin')
 console.log('PASS two lost responses: refresh follows the short successor chain')
 const tabs=await Promise.all(Array.from({length:3},()=>browser.newPage()));await Promise.all(tabs.map(p=>p.goto(origin+'/admin',{waitUntil:'networkidle0'})))
 assert.ok(tabs.every(p=>new URL(p.url()).pathname==='/admin'));for(const p of tabs)await p.close()
 console.log('PASS real Cookie: concurrent tabs retain login')
 await page.evaluate(()=>fetch('/api/auth/logout',{method:'POST',credentials:'include'}));cleanupCookie=null
 await page.reload({waitUntil:'networkidle0'});await page.waitForSelector('#login-username')
 console.log('PASS logout: revoked login cannot be restored')
} finally {
 if(page&&!page.isClosed())await page.evaluate(()=>fetch('/api/auth/logout',{method:'POST',credentials:'include'})).catch(()=>{})
 if(cleanupCookie)await fetch(origin+'/api/auth/logout',{method:'POST',headers:{Cookie:'algohub_refresh='+cleanupCookie,'User-Agent':ua}}).catch(()=>{})
 await browser.close()
}
