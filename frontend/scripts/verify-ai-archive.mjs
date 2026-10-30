import puppeteer from 'puppeteer-core'
import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'

const fixture=JSON.parse(fs.readFileSync('../backend/target/ai-archive-fixture.json','utf8'))
const zip=fs.readFileSync('../backend/target/ai-learning-review-fixture.zip')
const output=path.resolve('.screenshots/ai-archive');fs.mkdirSync(output,{recursive:true})
const downloads=fs.mkdtempSync(path.join(output,'downloads-'))
const browser=await puppeteer.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,args:['--no-sandbox']})
const page=await browser.newPage(),errors=[],posts=[]
let fail=false
page.on('pageerror',e=>errors.push(e.message))
await browser.defaultBrowserContext().overridePermissions('http://127.0.0.1:5174',['clipboard-read','clipboard-write','clipboard-sanitized-write'])
await page.setRequestInterception(true)
page.on('request',async req=>{
 const u=new URL(req.url());if(!u.pathname.startsWith('/api/'))return req.continue()
 if(u.pathname==='/api/ai-archive/skill')return req.respond({status:200,contentType:'application/zip',body:zip})
 if(u.pathname==='/api/ai-archive'){
  posts.push(JSON.parse(req.postData()))
  return req.respond({status:fail?500:200,contentType:'application/json',body:fail?JSON.stringify({success:false,message:'测试生成失败'}):JSON.stringify(fixture)})
 }
 // 手机底栏要读账号列表（显示需要处理的小红点），给个空列表
 if(u.pathname==='/api/accounts')return req.respond({status:200,contentType:'application/json',body:JSON.stringify({success:true,data:[]})})
 const user={id:1,displayName:'档案测试',role:'STUDENT',emailVerified:true,memberships:[{schoolId:1,status:'APPROVED',schoolName:'测试学校'}],privacy:{showOnRanking:true},bindings:[]}
 if(u.pathname!=='/api/auth/refresh')errors.push('Unexpected API '+u.pathname)
 return req.respond({status:200,contentType:'application/json',body:JSON.stringify({success:true,data:{accessToken:'test-only',user,expiresInSeconds:3600}})})
})
async function click(text){await page.$$eval('button',(buttons,text)=>{const b=buttons.find(b=>b.textContent.includes(text));if(!b)throw new Error('Missing button '+text);b.click()},text)}
async function downloaded(ext){for(let i=0;i<60;i++){const name=fs.readdirSync(downloads).find(n=>n.endsWith(ext));if(name)return fs.readFileSync(path.join(downloads,name));await new Promise(r=>setTimeout(r,100))}throw new Error('Missing download '+ext)}
try{
 await page.setViewport({width:1440,height:1000})
 await page.goto('http://127.0.0.1:5174/ai-archive',{waitUntil:'networkidle0'})
 await page.waitForSelector('[data-test=ai-archive]')
 await page.type('textarea[data-test=goal]','希望更清楚地解释自己的代码')
 await click('生成档案预览');await page.waitForSelector('[data-test=preview]')
 assert.equal(posts[0].goal,'希望更清楚地解释自己的代码');assert.equal(posts[0].includeDetails,false)
 assert.equal(await page.$eval('[data-test=preview]',e=>e.value),fixture.data.markdown.replace(/\r\n/g,'\n'))
 assert.equal(await page.$eval('ol[aria-label="两个步骤"] [aria-current=step]',e=>e.textContent.trim()),'2检查后复制给 AI')
 assert.equal(await page.evaluate(()=>document.body.innerText.includes('第 2 步在这里出现')),false)
 await page.screenshot({path:path.join(output,'desktop.png'),fullPage:true})
 await page.bringToFront();await page.click('[data-test=copy]');await page.waitForFunction(()=>document.body.innerText.includes('已复制档案'))
 assert.equal((await page.evaluate(()=>navigator.clipboard.readText())).replace(/\r\n/g,'\n'),fixture.data.markdown.replace(/\r\n/g,'\n'))
 const cdp=await page.createCDPSession();await cdp.send('Page.setDownloadBehavior',{behavior:'allow',downloadPath:downloads})
 await click('下载 Markdown');assert.equal((await downloaded('.md')).toString('utf8'),fixture.data.markdown)
 await click('下载 JSON');assert.deepEqual(JSON.parse((await downloaded('.json')).toString('utf8')),fixture.data.archive)
 await page.click('[data-test=skill-toggle]');await click('下载分析 Skill');assert.deepEqual(await downloaded('.zip'),zip)
 await page.setViewport({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false)
 assert.equal(await page.evaluate(()=>[...document.querySelectorAll('a')].some(a=>a.textContent.includes('返回个人档案')&&a.getClientRects().length)),false)
 await page.screenshot({path:path.join(output,'mobile.png'),fullPage:true})
 await page.type('textarea[data-test=goal]','，每天复盘');await page.waitForFunction(()=>!document.querySelector('[data-test=preview]'))
 await page.click('[data-test=include-details]');fail=true;await click('生成档案预览');await page.waitForFunction(()=>document.body.innerText.includes('测试生成失败'))
 fail=false;await click('生成档案预览');await page.waitForSelector('[data-test=preview]');assert.equal(posts.at(-1).includeDetails,true)
 await page.evaluate(()=>localStorage.setItem('algohub-theme','dark'));await page.reload({waitUntil:'networkidle0'})
 await click('生成档案预览');await page.waitForSelector('[data-test=preview]');await page.screenshot({path:path.join(output,'mobile-dark.png'),fullPage:true})
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false)
 assert.deepEqual(errors,[])
 console.log(JSON.stringify({result:'passed',checks:['request form','step indicator','mobile top bar','preview','clipboard','Markdown/JSON/Skill downloads','mobile overflow','stale preview invalidation','error retry','dark mode'],screenshots:output}))
}finally{await browser.close()}
