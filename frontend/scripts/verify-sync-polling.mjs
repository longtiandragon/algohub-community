import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'

// 平台账号页「一键同步全部」的进度跟踪（composables/useSyncAll.ts）和页面数据加载（composables/useAccountsPage.ts）：
// 用真实代码，接口、计时器、提示都换成可控的替身。
function load(file) {
  const source = fs.readFileSync(new URL(`../src/composables/${file}`, import.meta.url), 'utf8')
    .replace(/^import .*$/gm, '')
    .replace(/^export /gm, '')
  return ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText
}
const compiled = load('useAccountsPage.ts') + '\n' + load('useSyncAll.ts')

function fixture(getOverride) {
  const messages = [], timers = new Map(), unmounts = []
  let nextTimer = 0, fail = false, pending = null
  let rows = [{ accountId: 1, status: 'SUCCESS' }, { accountId: 2, status: 'SUCCESS' }]
  const context = vm.createContext({
    ref: value => ({ value }), reactive: value => value,
    computed: getter => ({ get value() { return getter() } }), watch() {}, onMounted() {},
    onBeforeUnmount: callback => { unmounts.push(callback) },
    // 账号列表来自账号 store：这里只要 reload 能成功
    useAccountsStore: () => ({ accounts: [], error: '', reload: async () => true }),
    get: async path => {
      if (getOverride) return getOverride(path)
      if (pending) await pending
      if (fail) throw new Error('simulated offline')
      if (path === '/stats/profile') throw new Error('Polling must not load the full profile')
      return path === '/sync/tasks' ? rows : []
    },
    post: async () => { throw new Error('这里的测试不提交同步') },
    ElMessage: Object.fromEntries(['success', 'warning', 'info'].map(kind => [kind, text => messages.push({ kind, text })])),
    notifyError: error => messages.push({ kind: 'error', text: error.message }),
    setTimeout: callback => { timers.set(++nextTimer, callback); return nextTimer },
    clearTimeout: id => timers.delete(id)
  })
  vm.runInContext(compiled + `
    const page = useAccountsPage()
    const sync = useSyncAll({ load: page.load, tasks: page.tasks, hasAccounts: () => true })
    globalThis.test = {
      load: page.load, tasks: page.tasks, loading: page.loading,
      pollSyncAll: sync.pollSyncAll, syncingAll: sync.syncingAll, syncAllResult: sync.syncAllResult,
      syncProgress: sync.syncProgress, setDeadline: sync.followUntil
    }`, context)
  const api = context.test
  api.syncAllResult.value = { accounts: [{ accountId: 1 }, { accountId: 2 }] }
  api.syncingAll.value = true
  api.setDeadline(Date.now() + 90000)
  return {
    api, messages, timers,
    unmount: () => unmounts.forEach(callback => callback()),
    rows: value => { rows = value }, fail: () => { fail = true }, pending: value => { pending = value }
  }
}

const missing = fixture()
missing.rows([{ accountId: 1, status: 'SUCCESS' }])
await missing.api.pollSyncAll()
assert.equal(missing.api.syncingAll.value, true)
assert.equal(missing.messages.length, 0)
assert.equal(missing.timers.size, 1)

const offline = fixture()
await offline.api.pollSyncAll() // Populate a stale successful response.
offline.messages.length = 0
offline.api.syncingAll.value = true
offline.fail()
await offline.api.pollSyncAll()
assert.equal(offline.api.syncingAll.value, true)
assert.equal(offline.messages.length, 0)
assert.match(offline.api.syncProgress.value, /正在重试/)

const finished = fixture()
await finished.api.pollSyncAll()
assert.equal(finished.api.syncingAll.value, false)
assert.equal(finished.messages[0].kind, 'success')

const failed = fixture()
failed.rows([{ accountId: 1, status: 'SUCCESS' }, { accountId: 2, status: 'FAILED' }])
await failed.api.pollSyncAll()
assert.equal(failed.messages[0].kind, 'warning')

const timeout = fixture()
timeout.rows([])
timeout.api.setDeadline(0)
await timeout.api.pollSyncAll()
assert.equal(timeout.api.syncingAll.value, false)
assert.equal(timeout.timers.size, 0)
assert.match(timeout.api.syncProgress.value, /尚未确认/)

const leaving = fixture()
let release
leaving.pending(new Promise(resolve => { release = resolve }))
const request = leaving.api.pollSyncAll()
leaving.unmount()
release()
await request
assert.equal(leaving.messages.length, 0)
assert.equal(leaving.timers.size, 0)
assert.equal(leaving.api.tasks.value.length, 0, 'Unmounted views must not apply a late response')

// A manual refresh overtakes an older poll. The older snapshot must not win.
const deferred = []
const racing = fixture(path => new Promise((resolve, reject) => deferred.push({ path, resolve, reject })))
const oldLoad = racing.api.load()
const oldCalls = deferred.splice(0)
const newLoad = racing.api.load({ silent: true })
const newCalls = deferred.splice(0)
for (const call of newCalls) call.resolve(call.path === '/sync/tasks' ? [{ accountId: 1, status: 'RUNNING' }] : [])
assert.equal(await newLoad, true)
for (const call of oldCalls) call.resolve(call.path === '/sync/tasks' ? [{ accountId: 1, status: 'SUCCESS' }] : [])
assert.equal(await oldLoad, false)
assert.equal(racing.api.tasks.value[0].status, 'RUNNING')
assert.equal(racing.api.loading.value, false)
assert.equal(racing.messages.length, 0)
const staleFailure = racing.api.load()
const failedCalls = deferred.splice(0)
const activeLoad = racing.api.load()
const activeCalls = deferred.splice(0)
for (const call of failedCalls) call.reject(new Error('old request failed'))
assert.equal(await staleFailure, false)
assert.equal(racing.api.loading.value, true, 'Old failure must not hide the current loading state')
assert.equal(racing.messages.length, 0, 'Old failure must not show a stale error')
for (const call of activeCalls) call.resolve([])
assert.equal(await activeLoad, true)
assert.equal(racing.api.loading.value, false)
console.log('PASS: missing tasks, stale response after failure, success, failure, timeout, unmount during request, out-of-order responses')
