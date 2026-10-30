export interface PassedProblem {
  pid: string
  title: string
  difficulty: number | null
  url: string
  tags: string[]
}

// Only read the JSON script; never insert or execute the supplied HTML.
export function parsePractice(html: string) {
  const scripts = html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi)
  let context: any
  for (const match of scripts) {
    if (/\bid\s*=\s*["']lentille-context["']/i.test(match[1]!)) {
      context = JSON.parse(match[2]!)
      break
    }
  }
  if (!Array.isArray(context?.data?.passed)) {
    throw new Error('未找到已通过题目。请选择正常练习页的完整响应，不要选择防护页。')
  }
  const unique = new Map<string, PassedProblem>()
  for (const p of context.data.passed) {
    if (typeof p?.pid !== 'string' || !/^[a-z0-9_-]+$/i.test(p.pid) || typeof p.name !== 'string') {
      throw new Error('题目数据格式异常，未导入，请重新保存完整练习页。')
    }
    unique.set(p.pid, {
      pid: p.pid, title: p.name,
      difficulty: Number.isInteger(p.difficulty) ? p.difficulty : null,
      url: `https://www.luogu.com.cn/problem/${encodeURIComponent(p.pid)}`,
      tags: Array.isArray(p.tags) ? [...new Set<string>(p.tags.filter((t: unknown) => typeof t === 'string' && t.trim()))] : [],
    })
  }
  return {
    problems: [...unique.values()],
    uid: String(context.data.user?.uid ?? ''),
    excluded: Array.isArray(context.data.submitted) ? context.data.submitted.length : 0,
    reported: Number.isInteger(context.data.user?.passedProblemCount) ? context.data.user.passedProblemCount as number : null,
  }
}

export function passedCsv(problems: PassedProblem[], includeTags: boolean, grouped: boolean, difficultyLabel = '洛谷难度编号') {
  const cell = (value: unknown) => {
    let text = String(value ?? '')
    if (/^[\s]*[=+@-]/.test(text)) text = "'" + text
    return '"' + text.replaceAll('"', '""') + '"'
  }
  const header = [...(grouped ? ['标签分组'] : []), '题号', '题目名称', '题目链接', difficultyLabel, ...(includeTags ? ['算法标签'] : [])]
  const rows = problems.flatMap(p => (grouped ? (p.tags.length ? p.tags : ['未获取标签']) : ['']).map(tag => ({ p, tag })))
  if (grouped) rows.sort((a, b) => a.tag.localeCompare(b.tag, 'zh-CN') || a.p.pid.localeCompare(b.p.pid, undefined, { numeric: true }))
  return '\uFEFF' + [header.map(cell).join(','), ...rows.map(({ p, tag }) =>
    [...(grouped ? [tag] : []), p.pid, p.title, p.url, p.difficulty, ...(includeTags ? [p.tags.join('；')] : [])].map(cell).join(','))].join('\r\n')
}
