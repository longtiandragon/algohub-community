import { campusDate, shiftDate } from '@/utils/statistics'

/** 每日记录的指标：只看题数（电脑的近一年格子、手机的月历共用）。 */
export const DAILY_METRICS: { value: string; label: string }[] = [
  { value: 'FIRST_AC', label: '首次通过题数' }
]

export function metricLabel(metric: string): string {
  return DAILY_METRICS.find((m) => m.value === metric)?.label ?? metric
}

/** 某一天的量，如「通过 3 题」 */
export function metricAmount(value: number): string {
  return `通过 ${value} 题`
}

/** 一段时间的合计，如「共 42 题」 */
export function metricTotal(value: number): string {
  return `共 ${value} 题`
}

/**
 * 热力图五档的起点：0 → 空，1 → 1 档，2～3 → 2 档，4～5 → 3 档，6 及以上 → 4 档。
 * 固定阈值，不按最大值比例分，这样不同月份、不同同学之间颜色能直接比。每日记录格子和手机月历共用。
 */
export const HEAT_STEPS = [0, 1, 2, 4, 6] as const

export function heatLevel(value: number): number {
  let level = 0
  for (let i = 1; i < HEAT_STEPS.length; i++) if (value >= HEAT_STEPS[i]) level = i
  return level
}

/** 图例上每档的短写：0 / 1 / 2+ / 4+ / 6+ */
export const HEAT_LEGEND = HEAT_STEPS.map((step, i) => (i <= 1 ? String(step) : `${step}+`))

/** 某一档代表的量，如「通过 2～3 题」「通过 6 题及以上」「没有记录」（图例悬停时看） */
export function heatRange(level: number): string {
  if (level <= 0) return '没有记录'
  const low = HEAT_STEPS[level]
  const high = level < HEAT_STEPS.length - 1 ? HEAT_STEPS[level + 1] - 1 : null
  const span = high == null || low === high ? `${low}` : `${low}～${high}`
  return `通过 ${span} 题${high == null ? '及以上' : ''}`
}

/**
 * 每日记录看的「近一年」：今天所在那周（周一开头）是最后一列，往前再排 52 周，一共 53 列；今天以后的格子不画。
 * firstMonth 是第一列所在的月份：页面从这个月 1 号读起，手机月历能完整翻到这个月。
 * 这份数据也一定覆盖「累计通过」的近一年（今天往前共 365 天）和今年 1 月 1 日起、「每周新增」的最近 12 周。
 */
export function heatmapWindow(today = campusDate()) {
  const monday = shiftDate(today, -((new Date(`${today}T00:00:00Z`).getUTCDay() + 6) % 7))
  const from = shiftDate(monday, -52 * 7)
  return { from, to: today, firstMonth: from.slice(0, 7) }
}
