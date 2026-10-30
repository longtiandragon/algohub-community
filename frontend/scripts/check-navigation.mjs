// Build the frontend and start Vite preview on 5176 before running this browser regression.
// ALGOHUB_PREVIEW_URL and CHROME_PATH can override the local defaults.
import assert from 'node:assert/strict'
import puppeteer from 'puppeteer-core'
const origin = process.env.ALGOHUB_PREVIEW_URL || 'http://127.0.0.1:5176'
const browser = await puppeteer.launch({headless:true, executablePath:process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'})
const user = {id:1,username:'test-admin',displayName:'管理员',role:'ADMIN',email:null,emailVerified:true,onboardingCompleted:true,memberships:[],bindings:[],privacy:{profileVisibility:'PUBLIC',showOnRanking:true,showAccounts:true,showEmail:false,showStudentNo:false}}
const ranking = {scope:'GLOBAL',participantCount:0,myRank:null,neighbors:[],myRankNote:'',type:'WEEK',metric:'FIRST_AC',description:'',note:'',rows:[],coverage:[]}
async function setup(width=1440) {
 const page=await browser.newPage(); await page.setViewport({width,height:900});const errors=[];page.on('pageerror',e=>errors.push(e.message))
 const held={};await page.setRequestInterception(true)
 page.on('request',async r=>{
  const url=new URL(r.url())
  if(/RankingView-.*\.js$/.test(url.pathname)){held.module=r;return}
  if(url.pathname==='/api/stats/ranking'){held.api=r;return}
  if(url.pathname.startsWith('/api/')) {
   let data=[]
   if(url.pathname==='/api/auth/refresh') data={accessToken:'test-token',user}
   if(url.pathname==='/api/me')data=user
   if(url.pathname==='/api/auth/github/status')data={enabled:false,note:''}
   await r.respond({status:200,contentType:'application/json',body:JSON.stringify({success:true,data})});return
  }
  await r.continue()
 })
 await page.goto(origin+'/announcements',{waitUntil:'networkidle0'})
 return {page,held,errors}
}
async function click(page,path){await page.evaluate(p=>document.querySelector(`a[href="${p}"]`).click(),path)}
async function waitHeld(held,key){for(let i=0;i<100&&!held[key];i++)await new Promise(r=>setTimeout(r,20));assert.ok(held[key],key+' requested')}
async function busy(page){await page.waitForSelector('[data-test="navigation-loading"]',{visible:true,timeout:1000})}
try {
 for(const width of [1440,390]) {
  const {page,held,errors}=await setup(width)
  await click(page,'/ranking');await busy(page);await waitHeld(held,'module')
  assert.equal(new URL(page.url()).pathname,'/announcements')
  assert.ok(await page.$('nav a.is-active[href="/ranking"]'))
  if(width<768) assert.equal(await page.$eval('.mobile-topbar',e=>e.innerText),'刷题排行')
  if(process.env.ALGOHUB_SCREENSHOTS) await page.screenshot({path:process.env.ALGOHUB_SCREENSHOTS+`/navigation-${width}.png`,fullPage:true})
  await held.module.continue();await page.waitForFunction(()=>location.pathname==='/ranking');await waitHeld(held,'api')
  assert.equal(await page.$('[data-test="navigation-loading"]'),null)
  assert.ok(await page.$('[aria-busy="true"] .skeleton'),'API loading skeleton')
  // While data is outstanding another single click must still navigate.
  await click(page,'/announcements');await page.waitForFunction(()=>location.pathname==='/announcements')
  await held.api.respond({status:200,contentType:'application/json',body:JSON.stringify({success:true,data:ranking})})
  assert.equal(errors.length,0,errors.join('\n'));await page.close()
  console.log(`PASS ${width}px: immediate skeleton, active menu, delayed API and navigation`)
 }
 {
  const {page,held,errors}=await setup()
  await click(page,'/ranking');await busy(page);await waitHeld(held,'module')
  await click(page,'/settings');await page.waitForFunction(()=>location.pathname==='/settings')
  await held.module.continue();await page.waitForFunction(()=>!document.querySelector('[data-test="navigation-loading"]'))
  assert.equal(new URL(page.url()).pathname,'/settings');assert.equal(errors.length,0,errors.join('\n'));await page.close()
  console.log('PASS: rapid switching keeps the latest destination')
 }
 {
  const {page,held}=await setup();await click(page,'/ranking');await busy(page);await waitHeld(held,'module')
  await click(page,'/announcements');await page.waitForFunction(()=>!document.querySelector('[data-test="navigation-loading"]'))
  assert.equal(new URL(page.url()).pathname,'/announcements');await held.module.continue();await page.close()
  console.log('PASS: clicking the original menu cancels pending feedback immediately')
 }
 {
  const {page,held}=await setup();await click(page,'/ranking');await busy(page);await waitHeld(held,'module')
  const first=held.module;await first.abort()
  for(let i=0;i<100&&held.module===first;i++)await new Promise(r=>setTimeout(r,20))
  assert.notEqual(held.module,first,'stale chunk reloads the target once')
  await held.module.abort()
  await page.waitForFunction(()=>document.body.innerText.includes('页面加载失败，请刷新后重试'))
  assert.equal(await page.$('[data-test="navigation-loading"]'),null)
  assert.equal(new URL(page.url()).pathname,'/ranking');await page.close()
  console.log('PASS: stale chunk recovery is bounded and keeps the target destination')
 }
} finally {await browser.close()}
