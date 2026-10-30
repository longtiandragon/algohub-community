import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'

const source = readFileSync(new URL('../src/components/social/FriendActions.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
const script = compileScript(descriptor, { id: 'friend-actions-regression' })
const { outputText } = ts.transpileModule(script.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } })
function deferred() {
  let resolve, reject
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}
function harness(api) {
  const cleanup = [], feedback = [], exports = {}
  vm.runInNewContext(outputText, { exports, crypto: { randomUUID }, require(name) {
    if (name === 'vue') return { ...vue, onBeforeUnmount: fn => cleanup.push(fn) }
    if (name === 'element-plus') return { ElMessage: { success: text => feedback.push(text), info: text => feedback.push(text) } }
    if (name === '@/api/client') return { ...api, notifyError: error => feedback.push(error) }
    if (name === '@/stores/auth') return { useAuthStore: () => ({ me: { id: 1 } }) }
    throw new Error(`Unexpected import: ${name}`)
  } })
  const props = vue.reactive({ userId: 2, initial: { following: true, mutual: true } })
  const scope = vue.effectScope()
  const editor = scope.run(() => exports.default.setup(props, { expose() {}, emit() {} }))
  return { editor, props, feedback, destroy() { cleanup.forEach(fn => fn()); scope.stop() } }
}
const relationship = { following: true, mutual: true }
const templates = [{ code: 'TRAIN', text: '来加训吧' }]

// A slow compose request must not mount a teleported dialog after navigation.
{
  const response = deferred()
  const h = harness({ get: url => url.endsWith('/templates') ? Promise.resolve(templates) : response.promise })
  const opening = h.editor.compose()
  h.destroy()
  response.resolve(relationship)
  await opening
  assert.equal(h.editor.open.value, false, 'late compose response reopened an unmounted dialog')
}
// Repeated clicks while opening must share one request, preserving a later selection.
{
  const response = deferred(); let requests = 0
  const h = harness({ get: url => { requests++; return url.endsWith('/templates') ? Promise.resolve(templates) : response.promise } })
  const one = h.editor.compose(), two = h.editor.compose()
  assert.equal(requests, 2, 'duplicate compose requests were issued')
  response.resolve(relationship)
  await Promise.all([one, two])
  assert.equal(h.editor.open.value, true)
  h.destroy()
}
// Navigating from A to B while A's send is pending must not close B's composer.
{
  const response = deferred(), sent = []
  const h = harness({ get: async url => url.endsWith('/templates') ? templates : relationship,
    post: (url, body) => { sent.push({ url, body }); return response.promise } })
  h.editor.selected.value = 'TRAIN'
  await vue.nextTick()
  const sending = h.editor.send()
  h.props.userId = 3
  await vue.nextTick()
  await h.editor.compose()
  h.editor.selected.value = 'TRAIN'
  await vue.nextTick()
  response.resolve({ id: 42 })
  await sending
  assert.equal(sent[0].url, '/social/users/2/messages')
  assert.equal(h.editor.open.value, true, 'old send response closed the new recipient composer')
  assert.equal(h.feedback.length, 0, 'old recipient response leaked feedback into the new profile')
  h.destroy()
}
// Retrying a lost acknowledgement must keep the same idempotency key.
{
  const sent = []
  const h = harness({ post: async (url, body) => {
    sent.push({ url, body }); if (sent.length === 1) throw new Error('lost acknowledgement')
  } })
  h.editor.selected.value = 'TRAIN'
  await vue.nextTick()
  await h.editor.send(); await h.editor.send()
  assert.equal(sent.length, 2)
  assert.equal(sent[0].body.requestId, sent[1].body.requestId)
  h.destroy()
}
console.log('Friend actions: unmount, duplicate compose, recipient navigation and idempotent retry passed')
