import { get, post } from '@/api/client'
import { campusDate, shiftDate } from '@/utils/statistics'

export interface TrainingSchool {
  id: number
  name: string
  manage: boolean
}
export interface ContestMeta {
  scoring?: string | null
  title: string
  platform: string
  ref: string
  category: string
  url: string
  tasks: { index: string; key: string }[]
}
export type Targets = Record<string, number>
export interface ContestPreview {
  meta: ContestMeta
  years: number[]
  targets: Targets
}
export interface TrainingStudent {
  realName?: string | null
  userId: number
  name: string
  year: number
  solved: number
  goal: number
  status: 'MET' | 'PENDING' | 'UNBOUND' | 'SYNCING' | 'ERROR'
}
export interface WeeklyContest {
  myProgress?: TrainingStudent
  id: number
  weekStart: string
  meta: ContestMeta
  targets: Targets
  students: TrainingStudent[]
}
export interface ScoreCell {
  solved: boolean
  wrong: number | null
  score: number | null
  seconds: number | null
}
export interface ScoreRow {
  realName?: string | null
  userId: number
  name: string
  kind: 'OFFICIAL' | 'STAR' | 'VIRTUAL' | 'PRACTICE'
  cells: Record<string, ScoreCell>
  rank: number | null
  sourceRank?: number | null
  score: number | null
  penalty: number | null
}
export interface ContestDetail extends WeeklyContest {
  standingsRefreshFailed: boolean
  rows: ScoreRow[]
  problemStats: Record<string, { solved: number; tried: number }>
}

export function weekMonday(day = campusDate()) {
  const offset = (new Date(day + 'T00:00:00Z').getUTCDay() + 6) % 7
  return shiftDate(day, -offset)
}

export const loadSchools = () => get<TrainingSchool[]>('/weekly/schools')
export const loadContests = (school: number) => get<WeeklyContest[]>('/weekly/' + school)
export const previewContest = (school: number, url: string) =>
  get<ContestPreview>('/weekly/' + school + '/preview', { url })
export const publishContest = (school: number, url: string, targets: Targets, weekStart: string) =>
  post<number>('/weekly/' + school, { url, targets, weekStart })
export const loadDetail = (school: number, id: number) => get<ContestDetail>('/weekly/' + school + '/' + id)

export interface WeeklyItem extends WeeklyContest {
  audience: 'ALL' | 'SELECTED'
  status: 'PUBLISHED' | 'DRAFT' | 'SCHEDULED'
  publishAt: string | null
}
export interface WeeklyRankingRow {
  realName?: string | null
  rank: number
  userId: number
  name: string
  year: number
  solved: number
}
export const loadWeeklyOverview = (school: number, weekStart: string, allTime = false) =>
  get<{ contests: WeeklyItem[]; ranking: WeeklyRankingRow[] }>('/weekly/' + school + '/overview', { weekStart, allTime })

export interface WeeklyCard extends Pick<WeeklyItem, 'id' | 'audience' | 'status' | 'publishAt'> {
  meta: Pick<ContestMeta, 'title' | 'platform' | 'category'>
  myProgress?: { goal: number }
}
export const loadWeeklyHome = (school: number | undefined, weekStart: string, allTime: boolean) =>
  get<{ schools: TrainingSchool[]; contests: WeeklyCard[]; ranking: WeeklyRankingRow[] }>('/weekly/home', { school, weekStart, allTime })
