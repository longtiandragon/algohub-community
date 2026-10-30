import assert from 'node:assert/strict'
import fs from 'node:fs'
import puppeteer from 'puppeteer-core'
const executablePath=['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(p=>fs.existsSync(p))
const browser=await puppeteer.launch({executablePath,headless:true})
try {
 const page=await browser.newPage();let submitted
 await page.setRequestInterception(true)
 page.on('request',request=>{
  const url=new URL(request.url());if(!url.pathname.startsWith('/api/'))return request.continue()
  const user={id:1,username:'grade-test',role:'STUDENT',emailVerified:true,memberships:[]}
  let data=[]
  if(url.pathname.endsWith('/refresh'))data={accessToken:'mock',user}
  if(url.pathname.endsWith('/enrollment-years'))data=[2023,2024,2025,2026]
  if(url.pathname==='/api/schools')data=[{id:1,name:'测试学校'}]
  if(url.pathname.endsWith('/schools/select')){submitted=JSON.parse(request.postData());data={id:1,schoolId:1,status:'PENDING',enrollmentYear:submitted.enrollmentYear}}
  if(url.pathname==='/api/me')data=user
  request.respond({status:200,contentType:'application/json',body:JSON.stringify({success:true,data})})
 })
 await page.goto('http://127.0.0.1:5174/select-school',{waitUntil:'networkidle0'})
 assert.equal(await page.$eval('button.auth-button',el=>el.disabled),true)
 assert.equal(await page.$eval('#school-year option',el=>el.disabled),true)
 await page.select('#school-year','2025')
 await page.click('button.auth-button')
 await page.waitForFunction(()=>document.body.textContent.includes('已提交'))
 assert.equal(submitted.enrollmentYear,2025)
 console.log('Required grade: empty blocked, selected year submitted in school request (mock API).')
} finally {await browser.close()}
