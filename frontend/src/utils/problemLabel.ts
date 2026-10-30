/**
 * 题目的显示名：题号 + 标题。力扣的题号是英文 slug，标题里已带前端编号（如「91. 解码方法」），
 * 只显示标题，避免出现「decode-ways 91. 解码方法」这样的重复。
 */
export function problemLabel(p: { platform: string; problemKey: string; title?: string | null }): string {
  if (!p.title) return p.problemKey
  return p.platform === 'LEETCODE_CN' ? p.title : `${p.problemKey} ${p.title}`
}

/** 统一算法分类的显示名：没归进任何分类的原始标签，后端记为 UNMAPPED */
export function categoryName(category: string): string {
  return category === 'UNMAPPED' ? '未分类标签' : category
}

/**
 * 动态里那种紧凑写法：题号和题名分开，题号带平台缩写。
 * CF 2007B / P1047（洛谷题号本身带字母）/ 力扣 121 / ABC086 A；题名去掉题号里已有的前缀。
 */
export function problemCode(p: { platform: string; problemKey: string; title?: string | null }): { code: string; name: string } {
  const title = p.title ?? ''
  if (p.platform === 'LEETCODE_CN') {
    const match = /^(\d+)\.\s*(.*)$/.exec(title)
    return match ? { code: `力扣 ${match[1]}`, name: match[2] } : { code: '力扣', name: title || p.problemKey }
  }
  if (p.platform === 'CODEFORCES') return { code: `CF ${p.problemKey}`, name: title }
  if (p.platform === 'ATCODER') {
    const match = /^([a-z]+\d+)_([a-z]\d*)$/i.exec(p.problemKey)
    const code = match ? `${match[1].toUpperCase()} ${match[2].toUpperCase()}` : p.problemKey
    return { code, name: title.replace(/^[A-Za-z]\d*\.\s*/, '') }
  }
  return { code: p.problemKey, name: title }
}

/** 前面已有平台图标时用：题号不再带「CF」「力扣」（如最近通过、做题记录） */
export function problemCodeBare(p: { platform: string; problemKey: string; title?: string | null }): { code: string; name: string } {
  const { code, name } = problemCode(p)
  return { code: code.replace(/^(CF|力扣)\s*/, ''), name }
}
