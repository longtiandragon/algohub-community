import { readFileSync, writeFileSync } from 'node:fs'

const runtime = readFileSync(new URL('./browser-sync-runtime.txt', import.meta.url), 'utf8').trimEnd()
const versions = {}
for (const platform of ['luogu', 'qoj', 'leetcode']) {
  const path = new URL(`../public/userscripts/algohub-${platform}-sync.user.js`, import.meta.url)
  const source = readFileSync(path, 'utf8')
  const version = source.match(/^\/\/ @version\s+(\d+\.\d+\.\d+)\s*$/m)?.[1]
  if (!version) throw new Error(`Missing @version in ${platform} userscript`)
  versions[platform] = version
  const pattern = /  \/\/ BEGIN shared browser sync runtime[\s\S]*?  \/\/ END shared browser sync runtime/
  const next = pattern.test(source) ? source.replace(pattern, () => runtime)
    : source.replace('  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))', match => `${match}\n${runtime}`)
  if (process.argv.includes('--check')) {
    if (source !== next) throw new Error(`${platform} userscript runtime needs regeneration`)
  } else writeFileSync(path, next)
}

const manifest = new URL('../public/userscripts/versions.json', import.meta.url)
const json = JSON.stringify(versions, null, 2) + '\n'
if (process.argv.includes('--check')) {
  if (readFileSync(manifest, 'utf8').replaceAll('\r\n', '\n') !== json) throw new Error('Regenerate userscript versions.json')
} else writeFileSync(manifest, json)
