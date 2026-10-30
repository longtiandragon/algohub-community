import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import assert from 'node:assert/strict'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'
import { renderToString } from '@vue/server-renderer'

function evaluate(source, modules = {}) {
  const exports = {}
  const { outputText } = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } })
  vm.runInNewContext(outputText, { exports, require: name => modules[name] ?? {} })
  return exports
}
const statistics = evaluate(readFileSync(new URL('../src/utils/statistics.ts', import.meta.url), 'utf8'))
const now = Date.parse('2026-10-30T04:00:00Z')
for (const value of [null, undefined, '', 'invalid']) assert.equal(statistics.dayTime(value, now), '')
assert.equal(statistics.dayTime('2026-10-30T02:10:00Z', now), '今天 10:10')
assert.equal(statistics.dayTime('2026-10-29T02:10:00Z', now), '昨天 10:10')
assert.equal(statistics.dayTime('2025-12-03T02:10:00Z', now), '2025/12/3')

const source = readFileSync(new URL('../src/components/profile/RecentAccepted.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
const script = compileScript(descriptor, { id: 'recent-accepted-regression', inlineTemplate: true })
const component = evaluate(script.content, {
  vue,
  '@/utils/statistics': statistics,
  '@/utils/problemLabel': { problemCodeBare: row => ({ code: row.problemKey, name: row.title }) },
  '@/components/PlatformLogo.vue': { default: { render: () => null } },
  '@/components/DifficultyTag.vue': { default: { render: () => null } },
}).default
async function render(submittedAt) {
  const app = vue.createSSRApp(component, { rows: [{ platform: 'LUOGU', problemKey: 'B2150', title: '判断闰年', url: null, difficultyLabel: null, submittedAt }] })
  app.component('RouterLink', { render: () => null })
  return renderToString(app)
}
for (const value of [null, undefined, '', 'invalid']) {
  const html = await render(value)
  assert.match(html, /时间未知/)
  assert.doesNotMatch(html, /1970|<time[\s>]/)
}
const html = await render('2025-12-03T02:10:00Z')
assert.match(html, /<time datetime="2025-12-03T02:10:00Z"/)
assert.match(html, /2025\/12\/3/)
assert.doesNotMatch(html, /时间未知/)
console.log('Recent accepted: missing/invalid timestamps and valid Beijing dates passed')
