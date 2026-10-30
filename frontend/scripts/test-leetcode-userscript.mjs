import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import assert from 'node:assert/strict'

const source=readFileSync(new URL('../public/userscripts/algohub-leetcode-sync.user.js',import.meta.url),'utf8')
const fragment=source.slice(source.indexOf('  // Queries and enum'),source.indexOf('  function upload('))
const q=i=>({titleSlug:i===0?'Gu0c2T':`example-${i}`,title:'Example',frontendId:String(i+1),numSubmitted:1,lastSubmittedAt:'2025-01-02T00:00:00Z',questionStatus:'SOLVED'})
const sub=(id,status='AC',timestamp='1735776000')=>({id:String(id),status,langName:'C++',timestamp})
let questions, submissions, changed, wrongOwner, missing, totals, calls
const runtime = source.slice(source.indexOf('  // BEGIN shared browser sync runtime'), source.indexOf('  // END shared browser sync runtime'))
const context=vm.createContext({ autoKey: "test", GM_getValue:()=>null, GM_setValue:()=>{}, GM_deleteValue:()=>{}, Date,Set,Map,console,button:{},note:{},sleep:async()=>{},setTimeout,clearTimeout,AbortController,document:{cookie:''},fetch:async (_url,options)=>{
  const {query,variables:v}=JSON.parse(options.body)
  let data
  if(query.includes('userStatus')) { calls++; data={userStatus:{isSignedIn:true,userSlug:wrongOwner&&calls>1?'someone-else':'example-user',activeSessionId:1}} }
  else if(query.includes('userProfileUserQuestionProgressV2')) data={userProfileUserQuestionProgressV2:{numAcceptedQuestions:[{difficulty:'EASY',count:totals},{difficulty:'MEDIUM',count:0},{difficulty:'HARD',count:0}]}}
  else if(query.includes('userProgressQuestionList')) {
    const rows=questions.map(x=>({...x}))
    if(changed && calls>1) rows[0].numSubmitted++
    // Count reads separately from identity checks to exercise final snapshot validation.
    if(changed) calls++
    data={userProgressQuestionList:{totalNum:rows.length,questions:rows.slice(v.filters.skip,v.filters.skip+50)}}
  } else {
    const rows=submissions[v.questionSlug]
    const page=rows.slice(v.offset,v.offset+10)
    if(missing) page.pop()
    data={userProgressSubmissionList:{totalNum:rows.length,submissions:page}}
  }
  return {ok:true,json:async()=>({data})}
}})
vm.runInContext(runtime + fragment,context)
function reset(n=21) {
  questions=Array.from({length:n},(_,i)=>q(i)); submissions=Object.fromEntries(questions.map((q,i)=>[q.titleSlug,[sub(i+1)]]))
  changed=false;wrongOwner=false;missing=false;totals=n;calls=0
}
reset()
let pages=await context.readSnapshot('example-user')
assert.equal(pages[0].total,21)
assert.equal(pages[0].rows[0].pid,'Gu0c2T')
reset(1)
questions[0].numSubmitted=12
submissions.Gu0c2T=Array.from({length:12},(_,i)=>sub(i+1,i===1?'WA':'AC',String(1735776000-i*86400)))
pages=await context.readSnapshot('example-user')
assert.equal(pages[0].total,11) // repeated ACs preserved, WA not uploaded
assert.equal(Math.min(...pages[0].rows.map(r=>r.submitTime)),1735776000-11*86400)
reset();missing=true
await assert.rejects(context.readSnapshot('example-user'),/分页不完整/)
reset();submissions['example-1']=[sub(1)]
await assert.rejects(context.readSnapshot('example-user'),/重复/)
reset();totals++
await assert.rejects(context.readSnapshot('example-user'),/总数不一致/)
reset();wrongOwner=true
await assert.rejects(context.readSnapshot('example-user'),/账号或做题进度/)
reset();changed=true
await assert.rejects(context.readSnapshot('example-user'),/变化/)
reset();submissions.Gu0c2T=[sub(1,'PENDING')]
await assert.rejects(context.readSnapshot('example-user'),/状态未知/)
reset();submissions.Gu0c2T=[sub(1,'AC','2025-01-01T12:00:00')]
await assert.rejects(context.readSnapshot('example-user'),/缺少时区/)
reset(0)
assert.equal((await context.readSnapshot('example-user'))[0].total,0)
reset(1001)
pages=await context.readSnapshot('example-user')
assert.deepEqual(Array.from(pages,p=>p.rows.length),[500,500,1])
assert.equal(new Set(pages.flatMap(p=>Array.from(p.rows,r=>r.id))).size,1001)
assert.ok(source.includes('width: \'188px\''))
console.log('LeetCode userscript: pagination, >15 records, repeated AC, earliest time, completeness, identity and failure checks passed')
