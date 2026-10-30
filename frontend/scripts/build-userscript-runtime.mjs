import { readFileSync, writeFileSync } from 'node:fs'

const runtime = readFileSync(new URL('./browser-sync-runtime.txt', import.meta.url), 'utf8').trimEnd()
for (const platform of ['luogu', 'qoj', 'leetcode']) {
  const path = new URL(`../public/userscripts/algohub-${platform}-sync.user.js`, import.meta.url)
  const source = readFileSync(path, 'utf8')
  const pattern = /  \/\/ BEGIN shared browser sync runtime[\s\S]*?  \/\/ END shared browser sync runtime/
  const next = pattern.test(source) ? source.replace(pattern, () => runtime)
    : source.replace('  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))', match => `${match}\n${runtime}`)
  if (process.argv.includes('--check')) {
    if (source !== next) throw new Error(`${platform} userscript runtime needs regeneration`)
  } else writeFileSync(path, next)
}
