import puppeteer from 'puppeteer-core'
import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
const output=path.resolve('.screenshots/data-dashboard');fs.mkdirSync(output,{recursive:true})
const browser=await puppeteer.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,args:['--no-sandbox']})
const page=await browser.newPage(),errors=[],requests=[]
page.on('pageerror',e=>errors.push(e.message))
const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date()),year=Number(today.slice(0,4)),month=today.slice(0,7),now=new Date().toISOString()
const me={id:56,displayName:'新生测试同学',role:'STUDENT',emailVerified:true,memberships:[{id:1,schoolId:1,schoolName:'测试学校',status:'APPROVED',enrollmentYear:year,className:'计算机一班'}],privacy:{showOnRanking:true},bindings:[]}
const accounts=[{id:1,userId:56,platform:'CODEFORCES',handle:'fixture-cf',ownershipStatus:'VERIFIED',authMode:'PUBLIC',syncStatus:'OK',supportsHistory:true,historyComplete:true,lastSuccessAt:now,hasCredential:false},{id:2,userId:56,platform:'LEETCODE_CN',handle:'fixture-total',ownershipStatus:'RESOLVED',authMode:'PUBLIC',syncStatus:'FAILED',supportsHistory:false,historyComplete:false,lastSuccessAt:null,lastErrorMsg:'连接暂时不可用',hasCredential:false}]
const caps=accounts.map(a=>({platform:a.platform,displayName:a.platform==='CODEFORCES'?'Codeforces':'力扣（中国站）',homeUrl:'https://example.org',submissionHistory:a.supportsHistory,totalsSnapshot:true,syncSupported:true,notes:'测试数据'}))
const mine={userId:56,displayName:me.displayName,rank:56,position:56,score:12,enrollmentYear:year,className:'计算机一班',hasUnverifiedSource:false}
const rank={type:'WEEK',metric:'FIRST_AC',description:'近 7 天',note:'页面验证数据',rows:Array.from({length:50},(_,i)=>({...mine,userId:i+1,displayName:`同学${i+1}`,rank:i<2?1:i+1,position:i+1,score:100-i,solved:100-i,uniqueSolved:100-i})),myRank:mine,neighbors:[{...mine,userId:55,rank:55,position:55,displayName:'同学55'},mine,{...mine,userId:57,rank:57,position:57,displayName:'同学57'}],participantCount:60,myRankNote:'同分并列；基于完整筛选范围计算',coverage:[{platform:'CODEFORCES',users:60,lastSuccessAt:now}]}
const profile={platformSolved:[{platform:'CODEFORCES',solved:120}],totalSolvedAcrossPlatforms:120,crossPlatformMapping:{uniqueSolvedAfterMapping:120,mergedDuplicates:0,mappedSolvedRecords:0,note:'尚无跨平台映射'},snapshotOverview:accounts.map(a=>({...a,solvedTotal:a.id===1?120:80,capturedAt:now,rawExtra:null})),difficultyDistribution:[{platform:'CODEFORCES',difficulty:'800',source:'OFFICIAL',solved:70}],tagDistribution:[{category:'动态规划',solved:40},{category:'UNMAPPED',solved:20}],tagCoverage:{withTags:60,total:120},recentAccepted:[{platform:"CODEFORCES",title:"测试记录",problemKey:"100A",url:null,submittedAt:now,difficultyLabel:"800"}]}
let empty=false,fail=false
await page.setRequestInterception(true)
page.on('request',async req=>{
 const u=new URL(req.url());if(!u.pathname.startsWith('/api/'))return req.continue()
 requests.push(u.pathname+u.search)
 if(fail&&u.pathname==='/api/stats/heatmap')return req.respond({status:500,contentType:'application/json',body:JSON.stringify({success:false,message:'测试加载失败'})})
 let data
 switch(u.pathname){
 case '/api/auth/refresh':data={accessToken:'test-only',user:me,expiresInSeconds:3600};break
 case '/api/accounts':data=empty?[]:accounts;break
 case '/api/platforms/capabilities':data=caps;break
 case '/api/stats/profile':data=empty?{...profile,platformSolved:[],snapshotOverview:[]}:profile;break
 case '/api/stats/snapshots':data=empty?[]:profile.snapshotOverview;break
case '/api/activity/coverage':data=empty?[]:[{accountId:1,platform:'CODEFORCES',solvedCount:120,untimedCount:0}];break
 case '/api/stats/heatmap':data={from:u.searchParams.get('from'),to:u.searchParams.get('to'),timezone:'Asia/Shanghai',metrics:empty?{}:{FIRST_AC:{[today]:12},SUBMISSIONS:{[today]:18}},metricNotes:{FIRST_AC:'已确认首次通过'}};break
 case '/api/stats/ranking':data=empty?{...rank,rows:[],neighbors:[],myRank:null,participantCount:0}:rank;break
 case '/api/stats/monthly-report':data={month,from:`${month}-01`,to:today,generatedAt:now,timezone:'Asia/Shanghai',newSolved:12,activeDays:1,enrollmentYear:year,myRank:56,participantCount:60,rankNote:rank.myRankNote,note:'仅含已同步且首次通过时间可确认的记录。名次按当前登记信息重新计算，不是历史快照。',platforms:[{platform:'CODEFORCES',solved:12,firstAcAvailable:true,historyComplete:true,lastSuccessAt:now},{platform:'LEETCODE_CN',solved:0,firstAcAvailable:false,historyComplete:false,lastSuccessAt:null}]};break
 case '/api/history':data={rows:[],total:0};break
 case '/api/stats/rating-history':data=[];break
 case '/api/activity/streak':data={currentStreak:empty?0:3,longestStreak:empty?0:5,activeDays:empty?0:40,solvedToday:empty?0:12,note:'测试数据'};break
 case '/api/activity/achievements':data={unlockedCount:0,totalCount:0,achievements:[],nextTarget:null,categoryCoverage:[],difficultyPeaks:[],note:'测试数据'};break
 case '/api/contests':data={contests:[{id:1,platform:'CODEFORCES',contestKey:'1',title:'Codeforces Round (Div. 4)',startAt:new Date(Date.now()+3*3600e3).toISOString(),endAt:new Date(Date.now()+5*3600e3).toISOString(),durationSec:7200,status:'SCHEDULED',category:'Div.4',url:null,source:'TEST',favorited:false}],categories:['Div.4'],updatedAt:now,favoritedCount:0};break
 case '/api/activity/feed':data={items:empty?[]:[{id:1,userId:57,displayName:'同学57',platform:'CODEFORCES',problemKey:'100A',title:'测试记录',acAt:now,url:null}]};break
 default:errors.push('Unexpected API: '+u.pathname);data=[]
 }
 await req.respond({status:200,contentType:'application/json',body:JSON.stringify({success:true,data})})
})
async function visit(route,width=1440){await page.setViewport({width,height:1000});await page.goto('http://127.0.0.1:5174'+route,{waitUntil:'networkidle0'});await page.waitForSelector('.page, [data-test=page]');assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,'Page overflow '+route+' '+width)}
async function shot(name){await page.screenshot({path:path.join(output,name+'.png'),fullPage:true})}
try{
 await visit('/');assert.match(await page.$eval('[data-test=home-stats]',e=>e.textContent),/12/);await shot('home-desktop')
 await visit('/',390);await shot('home-mobile')
 // 排行（第 21 步新布局）：不在前 50 名时「我的位置」照样写名次；默认看本人年级的近 7 天；前三名上领奖台，其余在榜单里
 await visit('/ranking');const position=await page.$eval('[data-test=my-position]',e=>e.textContent);assert.match(position,/#56/);assert.match(position,/\/ 60 人/);assert(requests.some(u=>u.includes('type=WEEK')&&u.includes('year='+year)));assert.equal(await page.$$eval('[data-test=ranking-row]',rows=>rows.length),47);await shot('ranking-desktop')
 // 手机：榜单直接显示（不再折叠）；筛选在底部弹层里，指标带解释；「我的位置」滚出屏幕后底部固定显示「我的名次」
 await visit('/ranking',390);assert.equal(await page.$('details'),null,'手机上榜单和筛选不再折叠');assert((await page.$$eval('[data-test=ranking-row]',rows=>rows.filter(r=>r.getClientRects().length).length))>0)
 await page.$$eval('.filter-chip',chips=>chips.find(c=>c.textContent.includes('筛选')).click());await page.waitForSelector('.bottom-sheet',{visible:true});assert.match(await page.$eval('.bottom-sheet',e=>e.textContent),/比什么[\s\S]*首次通过题数[\s\S]*推荐[\s\S]*每道题只算第一次通过/);await page.keyboard.press('Escape');await new Promise(r=>setTimeout(r,500))
 assert.equal(await page.$('[data-test=my-rank-bar]'),null,'「我的位置」还在屏幕里时不显示底部名次');await page.evaluate(()=>scrollTo(0,document.body.scrollHeight));await page.waitForSelector('[data-test=my-rank-bar]',{visible:true});assert.match(await page.$eval('[data-test=my-rank-bar]',e=>e.textContent),/#56[\s\S]*12/);await page.evaluate(()=>scrollTo(0,0));await shot('ranking-mobile')
 // 概览（第 23 步起）：档案卡只剩两个数字（已收录通过、最长连续）、每周新增和累计通过两张卡片；每日记录是近一年按天的格子，图例写出每档题数，点有记录的那天
 await visit('/profile');{const card=await page.$eval('[data-test=profile-card]',e=>e.textContent);assert.match(card,/已收录通过[\s\S]*?\b120\s*题[\s\S]*最长连续[\s\S]*?\b5\s*天/);assert.doesNotMatch(card,/跨平台去重|入学以来有记录|有记录的天数/)}
 assert(requests.some(u=>u.startsWith('/api/stats/heatmap?')&&u.includes('to='+today)),'每日记录读近一年，到今天为止');assert.equal(await page.$eval('[data-test=heat-legend]',e=>e.textContent.replace(/\s+/g,'')),'012+4+6+');assert.match(await page.$eval('[data-test=page]',e=>e.textContent),/每周新增通过[\s\S]*累计通过/);await (await page.$('[data-test=cumulative-card]')).screenshot({path:path.join(output,'trends-desktop.png')})
 await page.click(`[data-test=daily-records] button[data-date="${today}"]`);await page.waitForFunction(date=>document.body.innerText.includes('正在查看 '+date+' 的记录'),{},today)
 assert(requests.some(u=>u.startsWith('/api/history?')&&u.includes('from='+today)&&u.includes('to='+today)))
 await page.evaluate(()=>scrollTo(0,0));await shot('profile-desktop')
 // 档案分了标签（第 22 步起）：点每日记录的某天会跳到「做题记录」；月度报告在「报告与导出」里
 assert.equal(new URL(page.url()).searchParams.get('tab'),'records','点每日记录的某天应跳到做题记录标签')
 await visit('/profile?tab=reports');assert(await page.$('.monthly-report'),'报告与导出标签里应有月度报告')
 const downloadDirectory=fs.mkdtempSync(path.join(output,'download-'))
 const downloadedReport=path.join(downloadDirectory,`月度数据报告-${month}.png`)
 const client=await page.createCDPSession();await client.send('Page.setDownloadBehavior',{behavior:'allow',downloadPath:downloadDirectory});await page.$$eval('.monthly-report button',buttons=>buttons.find(b=>b.textContent.includes('导出图片')).click());
 for(let i=0;i<50&&!fs.existsSync(downloadedReport);i++)await new Promise(r=>setTimeout(r,100));assert(fs.existsSync(downloadedReport),'PNG download missing');
 assert.equal(fs.readFileSync(downloadedReport).subarray(0,8).toString('hex'),'89504e470d0a1a0a','Download must be a PNG')
 const png=await page.$eval('.monthly-report canvas',c=>c.toDataURL('image/png').split(',')[1]);fs.writeFileSync(path.join(output,'monthly-report.png'),Buffer.from(png,'base64'))
 await visit('/profile',390);await shot('profile-mobile');await page.evaluate(()=>{localStorage.setItem('algohub-theme','dark')});await visit('/profile',390);await shot('profile-mobile-dark');await (await page.$('[data-test=cumulative-card]')).screenshot({path:path.join(output,'trends-mobile-dark.png')});await page.evaluate(()=>{localStorage.setItem('algohub-theme','light')})
 empty=true;await visit('/');assert.match(await page.$eval('[data-test=home-onboarding]',e=>e.textContent),/绑定第一个刷题账号/);assert.equal(await page.$('[data-test=home-stats]'),null,'新同学不显示数据块（不给出误导的空数字）');await shot('home-empty')
 empty=false;fail=true;await visit('/');assert.match(await page.$eval('[data-test=home-error]',e=>e.textContent),/近期统计暂时无法加载/)
 assert.deepEqual(errors,[]);console.log(JSON.stringify({result:'passed',screenshots:output,checks:['desktop/mobile overflow','cohort week default','off-list personal rank','ranking filter sheet','mobile my-rank bar','daily records click filters history','monthly PNG render','empty onboarding','load error'],requests:requests.length}))
}finally{await browser.close()}
