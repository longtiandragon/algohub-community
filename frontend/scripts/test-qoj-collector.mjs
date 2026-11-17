import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import assert from 'node:assert/strict'
import { DOMParser } from 'linkedom'

// Execute the actual server extractor, rather than a second implementation.
const source = readFileSync(new URL('../../deploy/qoj-collector/collector.py', import.meta.url), 'utf8')
const extract = vm.runInNewContext(`(${source.match(/EXTRACT = r"""([\s\S]*?)"""/)[1]})`, { DOMParser })
const locales = [
  { title: 'User profile', logout: 'Logout', motto: 'Motto', total: 'Accepted problems: 2 problems',
    headers: ['ID', 'Problem', 'Submitter', 'Result', 'Language', 'Submit time'], clock: 'Server Time', empty: 'None' },
  { title: '用户信息', logout: '登出', motto: '格言', total: 'AC 过的题目：共 2 道题',
    headers: ['ID', '题目', '提交者', '结果', '语言', '提交时间'], clock: '服务器时间', empty: '无' },
  { title: '用戶檔案', logout: '登出', motto: '格言', total: '已解決的問題：共 2 道題',
    // QOJ currently keeps simplified Chinese submission headers in this locale.
    headers: ['ID', '题目', '提交者', '结果', '语言', '提交时间'], clock: '伺服器時間', empty: '無' },
]
const document = (locale, body, title = 'Submissions - QOJ.ac') => `<html><head><title>${title}</title></head><body>
  <a href="//qoj.ac/logout">${locale.logout}</a>${body}<footer>${locale.clock}: 2026-01-02 12:00:00 (UTC+8)</footer></body></html>`
const profile = locale => document(locale, `<h4 class="list-group-item-heading">${locale.motto}</h4><p>proof-123</p>
  <h4 class="list-group-item-heading">${locale.total}</h4><p><a href="/problem/1">1</a><a href="/problem/2">2</a></p>`, `sample_user - ${locale.title} - QOJ.ac`)
const table = (locale, row) => `<table><tr>${locale.headers.map(h => `<th>${h}</th>`).join('')}</tr>${row}</table>`
const row = `<tr><td><a href="/submission/101">101</a></td><td><a href="/problem/1">Example</a></td>
  <td><a class="uoj-username">sample_user</a></td><td><span class="uoj-score" data-score="100" data-full="100">AC ✓</span></td>
  <td>C++17</td><td>2026-01-02 00:30:00</td></tr>`
for (const locale of locales) {
  const raw = profile(locale)
  const result = extract({ kind: 'profile', raw })
  assert.equal(result.error, undefined)
  assert.equal(result.total, 2)
  assert.equal(result.motto, 'proof-123')
  assert.deepEqual(Array.from(result.problemIds), ['1', '2'])
  assert.equal(extract({ kind: 'profile', raw: raw.replace('/problem/2', '/problem/1') }).error, 'PARSE_ERROR')
  assert.equal(extract({ kind: 'profile', raw: raw.replace(`<a href="//qoj.ac/logout">${locale.logout}</a>`, '') }).error, 'SESSION_EXPIRED')
  const submissions = document(locale, table(locale, row) + '<ul class="pagination"><li class="active">1</li><li><a href="/submissions?submitter=sample_user&page=2">2</a></li></ul>')
  const data = extract({ kind: 'submissions', raw: submissions })
  assert.equal(data.error, undefined)
  assert.equal(data.rows[0].submitter, 'sample_user')
  assert.equal(data.rows[0].result, 'AC ✓')
  assert.equal(data.rows[0].time, '2026-01-02 00:30:00')
  assert.equal(data.rows[0].language, 'C++17')
  assert.equal(data.current, '1')
  assert.equal(data.pagination.length, 1)
  assert.ok(data.sourceClock.endsWith('(UTC+8)'))
  const empty = extract({ kind: 'submissions', raw: document(locale, table(locale, `<tr><td colspan="6">${locale.empty}</td></tr>`)) })
  assert.equal(empty.empty, true)
  assert.equal(empty.rows.length, 0)
  assert.equal(extract({ kind: 'submissions', raw: submissions.replace(`<th>${locale.headers[2]}</th>`, '<th>unknown</th>') }).error, 'PARSE_ERROR')
  assert.equal(extract({ kind: 'submissions', raw: submissions.replace(`<th>${locale.headers[4]}</th>`, '<th>Result</th>') }).error, 'PARSE_ERROR')
}
for (const title of ['Login - QOJ.ac', '登录 - QOJ.ac', '登入 - QOJ.ac', '登錄 - QOJ.ac']) {
  assert.equal(extract({ kind: 'profile', raw: document(locales[0], '', title) }).error, 'SESSION_EXPIRED')
}
assert.equal(extract({ kind: 'profile', raw: document(locales[1], '<form id="challenge-form"></form>', 'Just a moment...') }).error, 'ACCESS_RESTRICTED')
console.log('QOJ collector: English, simplified/traditional Chinese, expiry, challenge and malformed-page checks passed')
