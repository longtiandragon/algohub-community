import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import assert from 'node:assert/strict'
import ts from 'typescript'

const source = readFileSync(new URL('../src/utils/difficulty.ts', import.meta.url), 'utf8')
const { outputText } = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } })
const exports = {}
vm.runInNewContext(outputText, { exports })
const { difficultyStyle } = exports
for (const label of [null, '', '-1', '未评级']) assert.equal(difficultyStyle('NOWCODER', null, label).unknown, true)
for (const rating of [0, 100, 1600, 2900]) {
  assert.equal(difficultyStyle('NOWCODER', null, String(rating)).text, String(rating))
  assert.equal(difficultyStyle('NOWCODER', rating, null).unknown, false)
}
assert.equal(difficultyStyle('CODEFORCES', null, 'CF 1600').text, '1600')
assert.equal(difficultyStyle('ATCODER', null, 'AtCoder Problems 估计 -100').text, '115')
assert.match(difficultyStyle('ATCODER', null, 'AtCoder Problems 估计 1600').title, /第三方估计/)
console.log('Difficulty display: source ratings, unrated sentinels and negative AtCoder estimates passed')
