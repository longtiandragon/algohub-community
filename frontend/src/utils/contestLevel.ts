/**
 * 哪些比赛适合新手：前几题很简单、刚学完语法也能做出一两题的场次。
 * - Codeforces Div. 3 / Div. 4；
 * - AtCoder Beginner Contest（ABC）；
 * - 牛客小白月赛、牛客周赛；
 * - 标题里写了 Beginner / 入门 / 新手 的。
 * 力扣周赛、双周赛前两题不难，但后两题偏难、时间只有 90 分钟，不标（和设计画布一致）。
 * 只看标题和类别，不访问源站；首页的近期比赛和比赛日历共用这一条规则。
 */
const CATEGORIES = new Set(['Div.3', 'Div.4', 'ABC'])
const TITLES = [/Div\.?\s*[34]\b/i, /\bBeginner\b/i, /\bABC\s*\d+/, /小白/, /入门/, /新手/]

export function beginnerFriendly(contest: { platform?: string; category: string | null; title: string }): boolean {
  if (CATEGORIES.has(contest.category ?? '')) return true
  if (TITLES.some((pattern) => pattern.test(contest.title))) return true
  return contest.platform === 'NOWCODER' && contest.title.includes('周赛')
}
