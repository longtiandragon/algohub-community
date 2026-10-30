import type { Ranking, RankingRow } from '@/api/types'

/**
 * 我在榜上的位置：前后各一名（按榜上顺序），以及离「前一名」还差几题。
 * 同分并列时名次相同，所以「前一名」取名次比我小的人里最近的那个，差的题数一定大于 0。
 * 前后几名（neighbors）只有上下两个位置，并列的人多时找不到比我高一档的，再到榜上（rows）找。
 */
export function rankContext(ranking: Ranking | null | undefined) {
  const me = ranking?.myRank
  if (!ranking || !me) return null
  const list = [...ranking.neighbors].sort((a, b) => a.position - b.position)
  const index = list.findIndex((row) => row.userId === me.userId)
  const around: RankingRow[] = index < 0 ? [me] : list.slice(Math.max(0, index - 1), index + 2)
  const pool = [...list, ...(ranking.rows ?? [])].sort((a, b) => a.position - b.position)
  const ahead = pool.filter((row) => row.rank < me.rank).at(-1) ?? null
  const gap = ahead ? (ahead.score ?? 0) - (me.score ?? 0) : null
  return { me, around, ahead, gap: gap !== null && gap > 0 ? gap : null }
}
