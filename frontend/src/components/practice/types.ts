export type ShareScope = 'PRIVATE' | 'SCHOOL' | 'PUBLIC'

export interface ProblemList {
  id: number
  code: string
  title: string
  description: string | null
  category: string | null
  platform: string | null
  source: 'MANUAL' | 'GENERATED'
  generatedRule: string | null
  visible: boolean
  sortOrder: number
  itemCount: number | null
  solvedCount: number | null
  createdAt: string | null
  ownerId: number | null
  schoolId?: number | null
  shareScope: ShareScope
  ownerName: string | null
  favorited: boolean | null
  favoriteCount: number | null
}

export const SHARE_SCOPE_OPTIONS: { value: ShareScope; label: string; description: string }[] = [
  { value: 'PRIVATE', label: '仅自己', description: '只有你能看到' },
  { value: 'SCHOOL', label: '本校可见', description: '同校同学能看到、收藏' },
  { value: 'PUBLIC', label: '全站公开', description: '所有人都能看到' }
]

/** 平台限定为已接入题库同步的两个站点。 */
export const LIST_PLATFORM_OPTIONS: { value: string; label: string }[] = [
  { value: 'CODEFORCES', label: 'Codeforces' },
  { value: 'ATCODER', label: 'AtCoder' }
]

/** 去掉首尾空白；空串按「不填」处理，交给后端用默认值。 */
export function trimmed(value: string): string | undefined {
  const text = value.trim()
  return text ? text : undefined
}

export interface ListItem {
  itemId: number
  position: number
  note: string | null
  problemId: number
  platform: string
  problemKey: string
  title: string | null
  url: string | null
  difficultyLabel: string | null
  difficultyValue: number | null
  difficultySource: string | null
  solvedCount: number | null
  solvedByMe: boolean
  tags: { tagRaw: string; category: string }[]
}

export interface ListDetail {
  list: ProblemList
  items: ListItem[]
  itemCount: number
  solvedCount: number
  note: string
}

export interface LeaderboardRow {
  userId: number
  displayName: string
  avatarUrl: string | null
  solvedCount: number
  lastSolvedAt: string | null
}

export interface LeaderboardResponse {
  listId: number
  title: string
  itemCount: number
  rows: LeaderboardRow[]
  note: string
}

/** 生成规则等文字里存的是平台代码（如 CODEFORCES）：显示时换成平台名 */
const PLATFORM_CODE = /\b(CODEFORCES|ATCODER|LUOGU|LEETCODE_CN|NOWCODER|HDU|QOJ|MATIJI|LANQIAO)\b/g
export function readablePlatforms(text: string | null | undefined, name: (code: string) => string): string {
  return text ? text.replace(PLATFORM_CODE, (code) => name(code)) : ''
}

/** 通过人数上万时写「万」，免得长数字把行挤换行 */
export function formatSolvers(value: number): string {
  return value >= 10000 ? `${(value / 10000).toFixed(1)} 万` : String(value)
}

/** 只放行 http(s) 链接，题目链接是从各平台抓来的 */
export function httpUrl(url: string | null | undefined): string | undefined {
  return url && /^https?:\/\//i.test(url) ? url : undefined
}

const WEEKDAY = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
const beijingParts = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23'
})
/** 北京时间的年月日时分和星期 */
export function beijingDate(value: string) {
  const date = new Date(value)
  const [day, time] = beijingParts.format(date).split(' ')
  const [y, m, d] = day.split('-').map(Number)
  const weekday = WEEKDAY[new Date(Date.UTC(y, m - 1, d)).getUTCDay()]
  return { y, m, d, time, weekday, day }
}

/** 活动起止：「9/21 周一 00:00 – 9/27 周日 23:59」；short 时只写日期「9/14 – 9/20」 */
export function activityRange(startAt: string, endAt: string, short = false): string {
  const s = beijingDate(startAt)
  const e = beijingDate(endAt)
  if (short) return `${s.m}/${s.d} – ${e.m}/${e.d}`
  const end = s.day === e.day ? e.time : `${e.m}/${e.d} ${e.weekday} ${e.time}`
  return `${s.m}/${s.d} ${s.weekday} ${s.time} – ${end}`
}

/** 离某个时刻还有多久：「今晚 23:59」「明天 19:00」「3 天后」 */
export function untilText(value: string, now = Date.now()): string {
  const target = beijingDate(value)
  const today = beijingDate(new Date(now).toISOString())
  const dayDiff = Math.round((Date.UTC(target.y, target.m - 1, target.d) - Date.UTC(today.y, today.m - 1, today.d)) / 86400000)
  if (dayDiff <= 0) return `${Number(target.time.slice(0, 2)) >= 18 ? '今晚' : '今天'} ${target.time}`
  if (dayDiff === 1) return `明天 ${target.time}`
  return `${dayDiff} 天后`
}

export type ActivityState = 'UPCOMING' | 'RUNNING' | 'ENDED' | 'UNKNOWN'
export type ActivityProblemStatus = 'TODO' | 'SOLVED_DURING' | 'SOLVED_BEFORE' | 'SOLVED_AFTER' | 'SOLVED_TIME_UNKNOWN'

export interface ActivityProblem {
  id: number
  platform: string
  problemKey: string
  title: string | null
  url: string | null
  difficultyLabel: string | null
  difficultyValue: number | null
  position: number
  acAt: string | null
  status: ActivityProblemStatus
}

export interface ActivityBoardRow {
  userId: number
  displayName: string
  /** 同学选的 OJ 头像；没选或取不到时为空，显示字标 */
  avatarUrl?: string | null
  solvedCount: number
  lastSolvedAt: string | null
}

export interface Activity {
  id: number
  title: string
  description: string | null
  listId: number | null
  listTitle: string | null
  listCode: string | null
  problemCount: number | null
  startAt: string
  endAt: string
  published: boolean
  /** SCHOOL 面向指定学校；PUBLIC 全站 */
  scope: 'SCHOOL' | 'PUBLIC'
  schoolId: number | null
  schoolName: string | null
  state: ActivityState
  progress: { duringCount?: number; beforeCount?: number; unknownTimeCount?: number; problemCount?: number }
  progressNote?: string
  problems?: ActivityProblem[]
  leaderboard?: ActivityBoardRow[]
  note?: string
}

export type BookmarkStatus = 'LATER' | 'REVIEW' | 'DONE'

export interface BookmarkRow {
  id: number
  problemId: number
  status: BookmarkStatus
  note: string | null
  remindOn: string | null
  due: boolean
  updatedAt: string
  platform: string
  problemKey: string
  title: string | null
  url: string | null
  difficultyLabel: string | null
  difficultyValue: number | null
  accepted: boolean
  acAt: string | null
  categories: string[]
}

export const BOOKMARK_STATUS: Record<BookmarkStatus, { label: string; tone: 'warning' | 'brand' | 'success' }> = {
  REVIEW: { label: '待复习', tone: 'warning' },
  LATER: { label: '稍后再做', tone: 'brand' },
  DONE: { label: '已过关', tone: 'success' }
}
