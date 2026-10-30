import type { Contest } from '@/api/types'

/** 列表行在比赛信息之外多带一个「我是否收藏」标记，收藏关系存在后端。 */
export type ContestRow = Contest & { favorited: boolean }

export type ContestPhase = 'upcoming' | 'live' | 'ended' | 'started'

/** 源站没给时长的场次开赛后只能说「已开始」，推不出是否结束 */
export function phaseOf(row: Contest, now: number): ContestPhase {
  const start = Date.parse(row.startAt)
  if (start > now) return 'upcoming'
  const end = row.endAt ? Date.parse(row.endAt) : row.durationSec ? start + row.durationSec * 1000 : null
  if (end == null) return 'started'
  return now < end ? 'live' : 'ended'
}

/** 结束时间：有 endAt 用 endAt，否则开始 + 时长；都没有时为空 */
export function endOf(row: Contest): number | null {
  if (row.endAt) return Date.parse(row.endAt)
  return row.durationSec ? Date.parse(row.startAt) + row.durationSec * 1000 : null
}
