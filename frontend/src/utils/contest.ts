import { campusDate, shiftDate } from '@/utils/statistics'

/** 比赛时间的显示：北京时间 */
const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
const timeFormat = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })

/** 今天 / 明天 / 周二（一周内）/ 10/3 */
export function contestDay(startAt: string, now = Date.now()) {
  const day = campusDate(new Date(startAt))
  const today = campusDate(new Date(now))
  if (day === today) return '今天'
  if (day === shiftDate(today, 1)) return '明天'
  const diff = Math.round((Date.parse(`${day}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`)) / 86_400_000)
  if (diff > 1 && diff < 7) return WEEKDAYS[new Date(`${day}T00:00:00Z`).getUTCDay()]
  const [, month, date] = day.split('-').map(Number)
  return `${month}/${date}`
}

export function contestTime(startAt: string) {
  return timeFormat.format(new Date(startAt))
}

/** 2 小时 30 分 */
export function contestLength(seconds: number | null | undefined) {
  if (!seconds) return ''
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.round((seconds % 3600) / 60)
  return [hours ? `${hours} 小时` : '', minutes ? `${minutes} 分` : ''].filter(Boolean).join(' ')
}

/** 还有多久开始：进行中 / 45 分钟后 / 3 小时后 / 2 天后 */
export function startsIn(startAt: string, endAt: string | null, now = Date.now()) {
  const start = Date.parse(startAt)
  if (start <= now) return endAt && Date.parse(endAt) > now ? '进行中' : '已结束'
  const minutes = Math.ceil((start - now) / 60_000)
  if (minutes < 60) return `${minutes} 分钟后`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时后`
  return `${Math.floor(hours / 24)} 天后`
}
