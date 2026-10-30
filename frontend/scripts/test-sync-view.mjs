import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import assert from 'node:assert/strict'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'

const deferred = () => {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const settle = () => new Promise(resolve => setImmediate(resolve))
for (const platform of ['Luogu', 'Qoj', 'LeetCode']) {
  const view = `${platform}BrowserSyncView`
  const { descriptor } = parse(readFileSync(new URL(`../src/views/${view}.vue`, import.meta.url), 'utf8'))
  const { content } = compileScript(descriptor, { id: view })
  const { outputText } = ts.transpileModule(content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } })
  const exports = {}, cleanup = [], gets = [], posts = [], deletes = [], errors = []
  const queue = (list, url, body) => { const task = deferred(); list.push({ ...task, url, body }); return task.promise }
  vm.runInNewContext(outputText, { exports, require(name) {
    if (name === 'vue') return { ...vue, onMounted() {}, onBeforeUnmount: fn => cleanup.push(fn) }
    if (name === '@/api/client') return {
      get: url => queue(gets, url), post: (url, body) => queue(posts, url, body),
      del: url => queue(deletes, url), notifyError: error => errors.push(error),
    }
    return {}
  } })
  const scope = vue.effectScope()
  const page = scope.run(() => exports.default.setup({}, { expose() {} }))
  page.selected.value = 1
  page.auto.value = true
  const oldBegin = page.begin()
  await page.begin()
  assert.equal(posts.length, 1, 'duplicate begin must be blocked')
  page.selected.value = 2
  gets.at(-1).resolve({ enabled: true })
  await settle()
  posts[0].resolve({ uid: 'example-one', ticket: 'old-ticket' })
  await oldBegin
  assert.equal(page.ticket.value, '', 'old account ticket must not appear under new account')
  assert.equal(page.autoEnabled.value, true)

  const oldRevoke = page.revoke()
  page.selected.value = 1
  gets.at(-1).resolve({ enabled: true })
  await settle()
  deletes[0].resolve()
  await oldRevoke
  assert.equal(page.autoEnabled.value, true, 'old revocation must not clear new account state')

  const begin = page.begin()
  posts.at(-1).resolve({ uid: 'example-one', ticket: 'new-ticket' })
  await begin
  assert.equal(page.ticket.value, 'new-ticket')
  const revoke = page.revoke()
  await page.begin()
  assert.equal(posts.length, 2, 'begin cannot race a pending revocation')
  deletes.at(-1).resolve()
  await revoke
  assert.equal(page.ticket.value, '', 'revoked ticket must no longer be copyable')
  assert.equal(page.autoEnabled.value, false)
  gets[0].resolve({ enabled: true })
  await settle()
  assert.equal(page.autoEnabled.value, false, 'late status must not resurrect revoked authorization')
  assert.equal(page.loading.value, false)

  const leaving = page.begin()
  cleanup.forEach(fn => fn())
  posts.at(-1).reject(new Error('request failed after leaving'))
  await leaving
  assert.equal(errors.length, 0)
  scope.stop()
  console.log(`${view}: account switching, stale status, duplicate operations and revocation passed`)
}
