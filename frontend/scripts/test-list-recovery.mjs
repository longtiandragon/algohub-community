import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import assert from 'node:assert/strict'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'

const settle = () => new Promise(resolve => setImmediate(resolve))
for (const view of ['FriendsView', 'MessagesView']) {
  const { descriptor } = parse(readFileSync(new URL(`../src/views/${view}.vue`, import.meta.url), 'utf8'))
  const { content } = compileScript(descriptor, { id: view })
  const { outputText } = ts.transpileModule(content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } })
  const exports = {}, cleanup = [], requests = []
  let fail = true, delayed = null
  const friends = view === 'FriendsView'
  const totals = { following: 2, followers: 1 }
  const result = params => ({
    items: [{ id: params.before ? 1 : 2 }], hasMore: !params.before, nextCursor: params.before ? 1 : 2,
    unread: 2, unreadByCategory: { SYSTEM: 1, FRIEND: 1 },
  })
  vm.runInNewContext(outputText, { exports, Event, window: { dispatchEvent() {} }, require(name) {
    if (name === 'vue') return { ...vue, onBeforeUnmount: fn => cleanup.push(fn) }
    if (name === '@/api/client') return {
      get: async (url, params) => {
        if (url.endsWith('/counts')) return totals
        requests.push(params)
        if (delayed) return delayed
        if (fail) throw new Error('temporary network failure')
        return result(params)
      }, notifyError() {},
    }
    return {}
  } })
  const scope = vue.effectScope()
  const page = scope.run(() => exports.default.setup({}, { expose() {} }))
  await settle()
  assert.ok(page.error.value, `${view}: first-page failure must be distinguishable from an empty list`)
  fail = false
  await page.retry()
  assert.deepEqual(Array.from(page.items.value, row => row.id), [2])
  assert.equal(page.error.value, '')

  fail = true
  await page.load(true)
  assert.deepEqual(Array.from(page.items.value, row => row.id), [2])
  fail = false
  await page.retry()
  assert.equal(requests.at(-1).before, 2, `${view}: retry must retain the failed page cursor`)
  assert.deepEqual(Array.from(page.items.value, row => row.id), [2, 1])

  let rejectOld
  delayed = new Promise((_, reject) => { rejectOld = reject })
  const oldRequest = page.load()
  delayed = null
  if (friends) page.type.value = 'FRIENDS'
  else page.category.value = 'FRIEND'
  await vue.nextTick(); await settle()
  rejectOld(new Error('old filter request failed'))
  await oldRequest
  assert.equal(page.error.value, '', `${view}: late failure must not overwrite the new filter`)
  assert.deepEqual(Array.from(page.items.value, row => row.id), [2])
  cleanup.forEach(fn => fn()); scope.stop()
  console.log(`${view}: first-page retry, append failure recovery and stale response isolation passed`)
}
