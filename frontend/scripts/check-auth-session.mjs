import assert from 'node:assert/strict'
import puppeteer from 'puppeteer-core'
const origin=process.env.ALGOHUB_PREVIEW_URL||'http://127.0.0.1:5176'
const browser=await puppeteer.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'})
const user={id:1,username:'test',displayName:'测试',role:'ADMIN',email:'test@example.com',emailVerified:true,onboardingCompleted:true,memberships:[],bindings:[],privacy:{profileVisibility:'PUBLIC',showOnRanking:true,showAccounts:true,showEmail:false,showStudentNo:false}}
async function setup(mode='ok') {
 const page=await browser.newPage();await page.setViewport({width:1200,height:900});let refreshes=0;let failProtected=false;let nextRefresh=mode
 await page.setRequestInterception(true)
 page.on('request',async r=>{
  const path=new URL(r.url()).pathname
  if(!path.startsWith('/api/')){await r.continue();return}
  let status=200,data=[]
  if(path==='/api/auth/refresh'){
   refreshes++;if(nextRefresh==='network'){await r.abort();return};if(nextRefresh==='401')status=401
   else if(nextRefresh==='503')status=503
   data={accessToken:'test-token',user}
  } else if(path==='/api/auth/github/link-context')data={maskedEmail:'te***@example.com',githubLogin:'test-github',hasPassword:false}
  else if(path==='/api/auth/github/link-login')data={token:{accessToken:'linked-token',user:{...user,bindings:[{provider:'GITHUB',login:'test-github'}]}}}
  else if(path==='/api/me')data=user
  else if(path==='/api/auth/github/status')data={enabled:false,note:''}
  else if(path==='/api/announcements'&&failProtected)status=401
  await r.respond({status,contentType:'application/json',body:JSON.stringify({success:status===200,data,errorKind:status===401?'UNAUTHENTICATED':null,message:status===401?'令牌已过期':null})})
 })
 return {page,getRefreshes:()=>refreshes,setMode:v=>nextRefresh=v,setProtected:v=>failProtected=v}
}
async function body(page,text){await page.waitForFunction(t=>document.body.innerText.includes(t),{},text)}
try {
 for(const mode of ['network','503']){
  const x=await setup(mode);await x.page.goto(origin+'/announcements');await body(x.page,'无需重新登录')
  assert.equal(new URL(x.page.url()).pathname,'/announcements');x.setMode('ok');await x.page.click('button');await body(x.page,'公告与资源');assert.ok(x.getRefreshes()>=2);await x.page.close()
  console.log('PASS startup '+mode+': preserves session and retry restores the page')
 }
 {
  const x=await setup('401');await x.page.goto(origin+'/announcements');await x.page.waitForFunction(()=>location.pathname==='/admin/login'||location.pathname==='/login');await x.page.close();console.log('PASS explicit expired session returns to login')
 }
 {
  const x=await setup();await x.page.goto(origin+'/announcements',{waitUntil:'networkidle0'});x.setProtected(true);x.setMode('network')
  // Reload the announcement list while logged in; the 401 triggers refresh, but a network error must not log out.
  await x.page.evaluate(()=>document.querySelector('a[href="/settings"]').click());await x.page.waitForFunction(()=>location.pathname==='/settings');await x.page.evaluate(()=>document.querySelector('a[href="/announcements"]').click())
  await body(x.page,'公告与资源暂时读不到');assert.ok(x.getRefreshes()>=2);assert.equal(new URL(x.page.url()).pathname,'/announcements');await x.page.close();console.log('PASS runtime refresh network failure does not redirect to login')
 }
 {
  const x=await setup();await x.page.goto(origin+'/oauth/github/link',{waitUntil:'networkidle0'});await x.page.type('#github-link-code','123456');await x.page.click('button[type=submit]')
  await x.page.waitForFunction(()=>location.pathname==='/settings'&&new URLSearchParams(location.search).get('section')==='oauth');await x.page.close();console.log('PASS verified GitHub link goes directly to third-party accounts')
 }
} finally {await browser.close()}
