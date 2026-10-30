/** 校内动态的一条：某位同学第一次通过某题（时间是已知最早的通过时间）。 */
export interface FeedItem {
  id: number
  following?: boolean
  userId: number
  problemId: number
  displayName: string
  avatarUrl: string | null
  platform: string
  problemKey: string
  title: string | null
  url: string | null
  difficultyLabel: string | null
  difficultyValue: number | null
  difficultySource: string | null
  acAt: string
  firstAcConfidence: 'EXACT' | 'EARLIEST_KNOWN' | 'UNKNOWN' | 'USER_REPORTED'
  source: string
  timeAccuracy: string
  /** 时间旁的短标签：最近一次通过 / 最早已知时间 / 时间未知；准确的首次通过没有 */
  timeTag?: string | null
  summary: string
}

export type BookmarkMark = 'LATER' | 'REVIEW' | 'DONE' | null
