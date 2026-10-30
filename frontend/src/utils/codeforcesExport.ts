import type { PassedProblem } from './luoguExport'

export interface HistoryProblem {
  platform: string
  accepted: boolean
  problemKey: string | null
  title: string | null
  url: string | null
  difficulty: string | null
  tags: string | null
}

export function codeforcesPassed(rows: HistoryProblem[]): PassedProblem[] {
  const unique = new Map<string, PassedProblem>()
  for (const row of rows) {
    if (row.platform !== 'CODEFORCES' || !row.accepted || !row.problemKey) continue
    const tags = (row.tags ?? '').split(' | ').map(t => t.trim()).filter(Boolean)
    const previous = unique.get(row.problemKey)
    if (previous) {
      previous.tags = [...new Set([...previous.tags, ...tags])]
      continue
    }
    const ratingText = row.difficulty?.replace(/^CF\s+/i, '').trim()
    const rating = ratingText ? Number(ratingText) : NaN
    unique.set(row.problemKey, {
      pid: row.problemKey, title: row.title ?? row.problemKey,
      url: row.url && /^https:\/\/codeforces\.com\//.test(row.url) ? row.url : 'https://codeforces.com/problemset',
      difficulty: Number.isFinite(rating) ? rating : null,
      tags: [...new Set(tags)],
    })
  }
  return [...unique.values()]
}
