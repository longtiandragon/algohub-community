import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import assert from 'node:assert/strict'
import { DOMParser } from 'linkedom'
import './test-qoj-collector.mjs'

// Exercise the shipped parser directly with the observed QOJ HTML structure.
const source = readFileSync(new URL('../public/userscripts/algohub-qoj-sync.user.js', import.meta.url), 'utf8')
const parser = source.slice(source.indexOf('  function parseDocument('), source.indexOf('  function upload('))
const snapshot = source.slice(source.indexOf('  const fingerprint ='), source.indexOf('  async function run('))
const nav = '<a href="/submissions?submitter=sample_user">My Submissions</a>'
const row = (id, pid = id, owner = 'sample_user', time = '2026-10-28T17:20:55+08:00') => `<tr>
<td><a href="/submission/${id}">#${id}</a></td><td><a href="/contest/2565/problem/${pid}">#${pid}. A+B</a></td>
<td><a class="uoj-username">${owner}</a></td><td><a data-score="100.000000" data-full="100.000000">AC ✓</a></td>
<td>C++23</td><td><time datetime="${time}">2026-10-28 17:20:55</time></td></tr>`
const page = (rows, more = false, next = 2) => `<html><head><title>Submissions - QOJ</title></head><body>${nav}<table>
<tr>${['ID','Problem','Submitter','Result','Language','Submit time'].map(x=>`<th>${x}</th>`).join('')}</tr>${rows}</table>
${more ? `<ul class="pagination"><li><a href="?page=${next}">${next}</a></li></ul>` : ''}</body></html>`
const profile = ids => `<html><head><title>Profile</title></head><body><h4>Accepted problems：${ids.length} problems</h4><p>${ids.map(id=>`<a href="/problem/${id}">${id}</a>`).join('')}</p></body></html>`
let pages, profileHtml, readCount
const runtime = source.slice(source.indexOf('  // BEGIN shared browser sync runtime'), source.indexOf('  // END shared browser sync runtime'))
const context = vm.createContext({ autoKey: "test", GM_getValue:()=>null, GM_setValue:()=>{}, GM_deleteValue:()=>{}, DOMParser,URL,Date,console,button:{},note:{},sleep:async()=>{},sourceText:async url=> {
  if (url.startsWith('/user/')) return profileHtml
  const n = Number(new URL(url,'https://qoj.ac').searchParams.get('page'))
  readCount++
  return typeof pages === 'function' ? pages(n,readCount) : pages[n-1]
}})
vm.runInContext(runtime + parser + snapshot,context)
const parse = (html) => context.parsePage(html,'sample_user',1)
assert.equal(parse(page(row(3301845,14452))).rows[0].submitTime,1793179255)
assert.equal(parse(page(row(3301845,14452))).rows[0].pid,'14452')
assert.throws(()=>parse(page(row(1,1,'someone'))),/账号/)
assert.throws(()=>parse(page(row(1,1,'sample_user','2026-10-28T17:20:55'))),/时区/)
assert.throws(()=>parse(page(row(1).replace('100.000000','50.000000'))),/AC/)
assert.throws(()=>parse(page(row(1).replace('AC ✓','100'))),/AC/)
assert.throws(()=>parse('<title>Login</title><input type="password">'),/登录/)
assert.throws(()=>parse(page('')),/空页/)
async function check(input, ids, rejection, localize = html => html) {
  pages=input; profileHtml=localize(profile(ids)); readCount=0
  if (rejection) await assert.rejects(context.readSnapshot('sample_user'),rejection)
  else return context.readSnapshot('sample_user')
}
const first = Array.from({length:10},(_,i)=>row(i+1)).join('')
const result=await check([page(first,true),page(row(11,10))],Array.from({length:10},(_,i)=>i+1))
assert.equal(result[0].total,11)
await check([page(first,true),page(row(10))],Array.from({length:10},(_,i)=>i+1),/重复/)
await check([page(row(1),true)],[1],/不完整/)
assert.equal((await check([page(row(1))],[1,2]))[0].total,1)
assert.equal((await check([page(row(1)+row(2))],[1]))[0].total,2)
await check((n,count)=>page(row(count===1 ? 1 : 2)),[1],/变化/)
assert.equal((await check([page('<tr><td>No submissions</td></tr>')],[]))[0].total,0)
const large=await check(n=>page(Array.from({length:Math.min(10,1001-(n-1)*10)},(_,i)=>row((n-1)*10+i+1,1)).join(''),n<101,n+1),[1])
assert.equal(readCount,102)
assert.deepEqual(Array.from(large,p=>p.rows.length),[500,500,1])
assert.equal(new Set(large.flatMap(p=>Array.from(p.rows,r=>r.id))).size,1001)
for (const traditional of [false, true]) {
  const labels = {
    'My Submissions': traditional ? '我的提交記錄' : '我的提交记录',
    'Problem': traditional ? '題目' : '题目', 'Submitter': '提交者',
    'Result': traditional ? '結果' : '结果', 'Language': traditional ? '語言' : '语言',
    'Submit time': traditional ? '提交時間' : '提交时间',
    'Submissions - QOJ': traditional ? '提交列表 - QOJ' : '提交记录 - QOJ',
    'Accepted problems：': traditional ? '已解決的問題：共 ' : 'AC 过的题目：共 ',
    ' problems': traditional ? ' 道題' : ' 道题', 'No submissions': traditional ? '無' : '无',
  }
  const localize = html => Object.entries(labels).reduce((text, [en, zh]) => text.replaceAll(en, zh), html)
  const translated = parse(localize(page(row(3301845,14452))))
  assert.equal(translated.rows[0].pid, '14452')
  assert.equal(translated.rows[0].submitTime, 1793179255)
  assert.throws(() => parse(localize(page(row(1,1,'someone')))), /账号/)
  assert.throws(() => parse(localize(page(row(1).replace('AC ✓','100')))), /AC/)
  assert.throws(() => parse(localize(page(row(1,1,'sample_user','2026-10-28T17:20:55')))), /时区/)
  assert.throws(() => parse(localize(page(''))), /空页/)
  assert.throws(() => parse(localize(page(row(1))).replace('sample_user">', 'someone">')), /账号/)
  assert.equal((await check([localize(page(first,true)),localize(page(row(11,10)))], Array.from({length:10},(_,i)=>i+1), null, localize))[0].total, 11)
  assert.equal((await check([localize(page('<tr><td colspan="6">No submissions</td></tr>'))], [], null, localize))[0].total, 0)
  await check([localize(page(first,true)),localize(page(row(10)))], [1], /重复/, localize)
}
for (const title of ['登录', '登入', '登錄']) assert.throws(() => parse(`<title>${title} - QOJ.ac</title>`), /登录/)
assert.throws(() => parse(page(row(1)).replace('<th>Language</th>', '<th>结果</th>')), /重复/)
console.log('QOJ userscript: parser, completeness, identity, changing source and 1001-record batch checks passed')
