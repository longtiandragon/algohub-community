import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const frontend = fileURLToPath(new URL('..', import.meta.url))
const root = path.resolve(frontend, '..')
const lock = JSON.parse(fs.readFileSync(path.join(frontend, 'package-lock.json'), 'utf8'))
const sections = ['AlgoHub third-party notices\nGenerated from installed locked packages. Licenses below retain their original terms.']
for (const name of ['LICENSE', 'THIRD_PARTY_NOTICES.md']) {
  sections.push(`${name}\n\n${fs.readFileSync(path.join(root, name), 'utf8')}`)
}
const manual = path.join(root, 'third-party-licenses')
for (const name of fs.readdirSync(manual).sort()) {
  if (fs.statSync(path.join(manual, name)).isFile()) {
    sections.push(`${name}\n\n${fs.readFileSync(path.join(manual, name), 'utf8')}`)
  }
}
let packages = 0
for (const [relative, info] of Object.entries(lock.packages).sort(([a], [b]) => a.localeCompare(b))) {
  if (!relative.startsWith('node_modules/')) continue
  const directory = path.resolve(frontend, relative)
  if (!directory.startsWith(path.join(frontend, 'node_modules') + path.sep)) throw new Error('Invalid lockfile package path')
  if (!fs.existsSync(directory)) continue // Optional packages for other operating systems.
  const files = fs.readdirSync(directory).filter(name => /^(licen[sc]e|notice|copying)([._-]|$)/i.test(name))
    .filter(name => fs.statSync(path.join(directory, name)).isFile()).sort()
  sections.push(`${relative} @ ${info.version}\nDeclared license: ${info.license ?? 'UNSPECIFIED'}\n` +
    files.map(name => `${name}\n${fs.readFileSync(path.join(directory, name), 'utf8')}`).join('\n\n'))
  packages++
}
fs.writeFileSync(path.join(frontend, 'public/THIRD_PARTY_LICENSES.txt'), sections.join('\n\n==========\n\n') + '\n')
console.log(`Prepared license notices for ${packages} installed package paths.`)
