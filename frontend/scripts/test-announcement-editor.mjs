import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import assert from 'node:assert/strict'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'

// Exercise the real editor setup with only its API and UI feedback replaced.
const source = readFileSync(new URL('../src/components/admin/AnnouncementsPanel.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
const script = compileScript(descriptor, { id: 'announcement-regression' })
const { outputText } = ts.transpileModule(script.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } })
const requests = [], warnings = [], errors = []
const exports = {}
vm.runInNewContext(outputText, { exports, require(name) {
  if (name === 'vue') return { ...vue, onMounted() {} }
  if (name === 'element-plus') return { ElMessage: { success() {}, warning(text) { warnings.push(text) } } }
  if (name === '@/api/client') return {
    get: async () => [], post: async (url, body) => { requests.push({ url, body }); return 1 },
    notifyError: error => errors.push(error),
  }
  return {}
} })
const editor = exports.default.setup({}, { expose() {} })
const entries = Array.from({ length: 10 }, (_, i) => String(i).repeat(1000))
editor.edit({ id: 42, title: '边界回归', content: entries.join('\n'), published: false, kind: 'UPDATE', items: entries, url: null })
assert.equal(editor.form.value.content.length, 10009)
await editor.save()
assert.equal(errors.length, 0)
assert.equal(requests.length, 1)
assert.equal(requests[0].url, '/admin/announcements/42')
assert.equal(requests[0].body.content, '')
assert.deepEqual(Array.from(requests[0].body.items), entries)

editor.edit({ id: 42, title: '顺序回归', content: '', published: false, kind: 'UPDATE', items: ['第一项', '第二项'], url: null })
editor.move(1, -1)
await editor.save()
assert.deepEqual(Array.from(requests[1].body.items), ['第二项', '第一项'])
editor.edit()
editor.form.value.title = '空白条目'
await editor.save()
assert.equal(requests.length, 2)
assert.match(warnings.at(-1), /空白条目/)
editor.form.value.items = [...entries, '多出的一字']
await editor.save()
assert.equal(requests.length, 2)
assert.match(warnings.at(-1), /10000/)
editor.form.value.kind = 'TEXT'
editor.form.value.content = '普通文字公告正文'
await editor.save()
assert.equal(requests[2].body.content, '普通文字公告正文')
assert.equal(requests[2].body.items.length, 0)
editor.edit({ id: 42, title: '版本回归', version: 'v2026.10.30', content: '更新', kind: 'TEXT', published: false })
assert.equal(editor.form.value.version, 'v2026.10.30')
await editor.save()
assert.equal(requests[3].body.version, 'v2026.10.30')
editor.edit()
assert.equal(editor.form.value.version, '')
console.log('Announcement editor: 10000-character re-edit, item ordering, empty/oversize validation and text notices passed')
