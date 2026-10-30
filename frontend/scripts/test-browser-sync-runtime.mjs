import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import assert from 'node:assert/strict'

const runtime = readFileSync(new URL('./browser-sync-runtime.txt', import.meta.url), 'utf8')
const baseTime = 1793300000000
function harness(extra = {}) {
  let now = baseTime
  const storage = new Map(), sleeps = []
  class Clock extends Date { static now() { return now } }
  const context = vm.createContext({ Date: Clock, AbortController, console,
    setTimeout: () => 1, clearTimeout: () => {}, autoKey: 'test.grant',
    button: {}, note: {}, retryButton: {}, resumeButton: {},
    GM_getValue: (key, fallback) => structuredClone(storage.get(key) ?? fallback),
    GM_setValue: (key, value) => storage.set(key, structuredClone(value)), GM_deleteValue: key => storage.delete(key),
    sleep: async ms => { sleeps.push(ms); now += ms }, ...extra })
  vm.runInContext(runtime, context)
  return { context, storage, sleeps, advance: ms => { now += ms } }
}

// Out-of-order requests must preserve output order and never exceed two workers.
{
  const {context} = harness()
  let active = 0, peak = 0
  const output = await context.mapLimited([3, 1, 2, 0], async n => {
    peak = Math.max(peak, ++active)
    await new Promise(resolve => setTimeout(resolve, n * 2))
    active--; return n
  })
  assert.equal(peak, 2)
  assert.deepEqual(Array.from(output), [3, 1, 2, 0])
  let finished = false
  await assert.rejects(context.mapLimited([0, 1, 2], async n => {
    if (!n) throw new Error('failed')
    await new Promise(resolve => setTimeout(resolve, 5)); finished = true
  }), /failed/)
  assert.ok(finished, 'in-flight workers must settle before a snapshot restarts')
}

// Shared pacing and Retry-After apply to every source request, including retries.
{
  let calls = 0
  const {context, sleeps} = harness({ fetch: async () => {
    calls++
    return calls === 1 ? {ok:false,status:429,headers:{get:()=> '12'}} : {ok:true,text:async()=> 'ok'}
  } })
  assert.equal(await context.fetchSource('/records'), 'ok')
  await context.fetchSource('/records')
  assert.equal(calls, 3)
  assert.ok(sleeps.includes(12000))
  assert.ok(sleeps.includes(3000), 'after 429 request starts slow down')
}
for (const status of [401, 403, 404, 500]) {
  let calls = 0
  const {context} = harness({fetch:async()=>{calls++;return {ok:false,status,headers:{get:()=>null}}}})
  await assert.rejects(context.fetchSource('/records'), new RegExp(String(status)))
  assert.equal(calls, status === 500 ? 3 : 1)
}
{
  let calls = 0
  const {context, sleeps} = harness({fetch:async()=>{calls++;return {ok:false,status:429,headers:{get:()=> '3600'}}}})
  await assert.rejects(context.fetchSource('/records'), /429/)
  await assert.rejects(context.fetchSource('/records'), /较长时间/)
  assert.equal(calls,1)
  assert.ok(sleeps.every(ms=>ms<300000), 'long cooldown stops instead of hanging queued workers')
}
{
  let calls=0
  const {context}=harness({setTimeout:fn=>{queueMicrotask(fn);return 1},fetch:async(_url,{signal})=>{
    calls++
    return new Promise((_resolve,reject)=>signal.addEventListener('abort',()=>reject(new Error('aborted'))))
  }})
  await assert.rejects(context.fetchSource('/records'), /aborted/)
  assert.equal(calls,3)
}
{
  let calls = 0
  const {context} = harness({fetch:async()=>{calls++;throw new Error('offline')}})
  await assert.rejects(context.fetchSource('/records'), /offline/)
  assert.equal(calls, 3)
}

// Execute the actual run function for each platform with network responses replaced.
for (const platform of ['luogu', 'qoj', 'leetcode']) {
  const source = readFileSync(new URL(`../public/userscripts/algohub-${platform}-sync.user.js`, import.meta.url), 'utf8').replaceAll('\r\n','\n')
  const transport = source.slice(source.indexOf('  function upload('),source.indexOf('  function autoBegin('))
  for (const status of [429, 502, 403, 200]) {
    let attempts=0
    const h=harness({endpoint:'https://example.test/import',GM_xmlhttpRequest:options=>{
      attempts++
      options.onload(attempts===1 ? {status,responseText:'<html>proxy error</html>'}
        : {status:200,responseText:JSON.stringify({success:true,data:{complete:true}})})
    }})
    vm.runInContext(transport,h.context)
    if(status===429 || status===502) {
      assert.equal((await h.context.uploadWithRetry('ticket',{})).complete,true)
      assert.equal(attempts,2)
    } else {
      await assert.rejects(h.context.uploadWithRetry('ticket',{}),e=>status!==403 || e.invalidTicket===true)
      assert.equal(attempts,1)
    }
  }
  const run = source.slice(source.indexOf('  async function run('), source.indexOf('  resumeButton.hidden = !pendingUpload()\n  resumeButton.addEventListener'))
  assert.ok(run.includes('async function run('))
  const uid = '123456', ticket = `${uid}.${'a'.repeat(43)}`
  const rows = Array.from({length: 1001}, (_, i) => ({id:String(i+1),uid,pid:'1001',submitTime:1700000000+i}))
  const pages = [0,500,1000].map((offset,i)=>({page:i+1,total:1001,perPage:500,rows:rows.slice(offset,offset+500)}))
  let reads = 0, uploads = [], fail = true, current = uid, malformed = false
  function fresh(storage) {
    const h = harness({
      successKey: 'success', attemptKey: 'attempt', window: { alert:()=>{} },
      currentUid: async()=>current, readSnapshot: async()=>{reads++;return structuredClone(pages)},
      upload: async(_ticket,p)=>{
        uploads.push(p.page)
        if (fail && p.page===2) throw Object.assign(new Error('offline'),{retryable:true})
        return {received:Math.min(p.page*500,p.total),total:p.total,complete:malformed || p.page===3,autoEnabled:false}
      }
    })
    if(storage) for(const [key,value] of storage) h.storage.set(key,structuredClone(value))
    vm.runInContext('let running = false;\n'+run,h.context)
    return h
  }
  let h=fresh()
  await h.context.run(ticket,false)
  assert.deepEqual(uploads,[1,2,2,2])
  assert.equal(reads,1)
  assert.equal(h.context.pendingUpload().next,1)
  assert.equal(h.storage.has('success'),false)
  // Refresh the userscript: progress is in manager storage, not only RAM.
  h=fresh(h.storage); uploads=[]; fail=false
  current='654321'
  await h.context.run(ticket,false)
  assert.equal(uploads.length,0,'switched accounts may not resume')
  current=uid
  await h.context.run(ticket,false)
  assert.deepEqual(uploads,[2,3])
  assert.equal(reads,1,'resume must not reread the source history')
  assert.equal(h.context.pendingUpload(),null)
  assert.ok(h.storage.has('success'))
  // Corrupt receipts never mark success.
  h=fresh(); malformed=true
  await h.context.run(ticket,false)
  assert.equal(h.storage.has('success'),false)
  assert.equal(h.context.pendingUpload().next,0)
  malformed=false
  const cache=structuredClone(h.context.pendingUpload())
  h.advance(20*60000+1)
  assert.equal(h.context.pendingUpload(),null)
  assert.equal(h.storage.has('test.grant.pending-upload-v1'),false)
  // A new ticket re-reads instead of reusing the old snapshot.
  h=fresh();fail=true
  await h.context.run(ticket,false)
  const before=reads
  await h.context.run(`${uid}.${'b'.repeat(43)}`,false)
  assert.equal(reads,before+1)
  for(const mutate of [s=>s.pages[0].rows.pop(),s=>s.uid='wrong',s=>s.next=-1,s=>s.pages[0].rows[1].id='1',s=>s.pages[1]=null]) {
    h=fresh();const bad=structuredClone(cache);mutate(bad)
    h.context.savePending(bad)
    assert.equal(h.context.pendingUpload(),null)
  }
  // Final response may be lost after commit: retry the identical final payload.
  h=fresh();let finalCalls=0;fail=false
  h.context.upload=async(_t,p)=>{
    if(p.page===3 && ++finalCalls===1) throw Object.assign(new Error('lost reply'),{retryable:true})
    return {received:Math.min(p.page*500,p.total),total:p.total,complete:p.page===3,autoEnabled:false}
  }
  await h.context.run(ticket,false)
  assert.equal(finalCalls,2)
  assert.equal(h.context.pendingUpload(),null)
  assert.ok(h.storage.has('success'))
  console.log(`${platform}: interrupted upload, refresh/resume, account isolation, expiry, corruption, new ticket and lost final reply passed`)
}
console.log('Shared runtime: bounded concurrency, ordered results, worker draining, source pacing and retry policy passed')
