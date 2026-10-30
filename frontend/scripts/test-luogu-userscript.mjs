import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import assert from 'node:assert/strict'
import { DOMParser } from 'linkedom'

const source = readFileSync(new URL('../public/userscripts/algohub-luogu-sync.user.js', import.meta.url), 'utf8')
const parser = source.slice(source.indexOf('  function parseResponse('), source.indexOf('  function upload('))
const snapshot = source.slice(source.indexOf('  const fingerprint ='), source.indexOf('  async function run('))
let records = [], calls = 0, mode = ''
const runtime = source.slice(source.indexOf('  // BEGIN shared browser sync runtime'), source.indexOf('  // END shared browser sync runtime'))
const context = vm.createContext({ autoKey: "test", GM_getValue:()=>null, GM_setValue:()=>{}, GM_deleteValue:()=>{},  DOMParser, URL, Date, button: {}, note: {}, sleep: async () => {}, sourceText: async url => {
  calls++
  const page = Number(new URL(url, 'https://www.luogu.com.cn').searchParams.get('page'))
  const rows = records.slice((page - 1) * 20, page * 20).map(row => ({ ...row }))
  if (mode === 'duplicate' && page === 2) rows[0] = records[0]
  if (mode === 'short' && page === 2) rows.pop()
  if (mode === 'changing' && calls > 1 && page === 1 && rows.length) rows[0].id = String(Number(rows[0].id) + calls)
  return JSON.stringify({ data: { records: { count: records.length, perPage: 20, result: rows } } })
} })
vm.runInContext(runtime + parser + snapshot, context)
function fixture(count) {
  calls = 0; mode = ''
  records = Array.from({ length: count }, (_, i) => ({ id: String(90000 + i), user: { uid: 123456 }, status: 12,
    problem: { pid: 'P1000', name: 'Example', difficulty: 1 }, submitTime: 1700000000 + i, language: 1 }))
}
fixture(1001)
const batches = await context.readSnapshot('123456')
assert.equal(calls, 52) // 51 个源站页和一次首页复核，源站请求没有被偷偷省略。
assert.equal(batches.length, 3) // 51 个上传请求降为 3 个。
assert.deepEqual(Array.from(batches, b => [b.page,b.total,b.perPage,b.rows.length]), [[1,1001,500,500],[2,1001,500,500],[3,1001,500,1]])
assert.equal(new Set(batches.flatMap(b => Array.from(b.rows, r => r.id))).size, 1001)
assert.equal(batches[0].rows[0].submitTime, 1700000000)
assert.equal(batches[2].rows[0].submitTime, 1700001000)
fixture(0)
assert.equal((await context.readSnapshot('123456'))[0].rows.length, 0)
fixture(500)
assert.equal((await context.readSnapshot('123456')).length, 1)
fixture(21); mode = 'duplicate'
await assert.rejects(context.readSnapshot('123456'), /持续变化/)
fixture(21); mode = 'short'
await assert.rejects(context.readSnapshot('123456'), /持续变化/)
fixture(1); mode = 'changing'
await assert.rejects(context.readSnapshot('123456'), /持续变化/)
fixture(1); records[0].user = { uid: 654321 }
await assert.rejects(context.readSnapshot('123456'), /UID/)
fixture(1); records[0].status = 6
await assert.rejects(context.readSnapshot('123456'), /状态/)
console.log('Luogu userscript: batching, empty/boundary pages, complete coverage, duplicate/short/changed pages and identity checks passed')
