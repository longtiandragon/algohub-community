import type { PlatformAccount } from '@/api/types'

export function campusDate(date = new Date()): string {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(date)
}

export function shiftDate(date: string, days: number): string {
  const value = new Date(`${date}T00:00:00Z`)
  value.setUTCDate(value.getUTCDate() + days)
  return value.toISOString().slice(0, 10)
}

/** 最近一轮有数据写入成功（部分成功也算已更新，失败的那一项在数据项里单独标出） */
export function updatedAccount(account: PlatformAccount): boolean {
  return ['OK', 'PARTIAL'].includes(account.syncStatus) && !!account.lastSuccessAt && account.ownershipStatus !== 'MISMATCH'
}

/**
 * 账号卡片上的「已通过」：平台统计和已收录的逐题明细取大的那个。
 * 力扣只公开最近 15 道明细、统计是 42，写 42；Codeforces、AtCoder 不另取统计，写明细数；
 * 牛客明细含只在 Tracker 通过的题，比牛客自己的统计多，写明细数。两个都没取得时为 null（写「—」，不写 0）。
 */
export function passedCount(account: PlatformAccount): number | null {
  const total = account.platformSolvedTotal
  const collected = account.collectedProblems
  if (total == null && collected == null) return null
  return Math.max(total ?? 0, collected ?? 0)
}

export const DATA_ITEM_LABEL: Record<string, string> = {
  TOTALS: '平台统计题数',
  SOLVED_LIST: '逐题通过列表',
  HISTORY: '提交历史（通过时间）',
  RATING: '比赛与 Rating'
}

export const DATA_ITEM_STATUS: Record<string, { label: string; type: 'success' | 'warning' | 'danger' }> = {
  SUCCESS: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  NEEDS_CREDENTIAL: { label: '需要登录态', type: 'warning' }
}

/**
 * 一句话说清这个账号的数据到底有多少、缺什么。平台统计、已收录明细、有通过时间的题数是三件事，
 * 不互相替代：例如力扣"统计 42 题，尚未获取逐题明细"，而不是"刷了 0 题"。
 */
/** 平台名与"统计"之间：以字母数字结尾的名字（如"杭电 OJ"）空一格 */
function spaced(platform: string): string {
  return /[A-Za-z0-9]$/.test(platform) ? `${platform} ` : platform
}

export function dataSummary(account: PlatformAccount, platform: string): string {
  const total = account.platformSolvedTotal
  const collected = account.collectedProblems ?? 0
  const timed = account.timedProblems ?? 0
  const items = account.dataItems ?? []
  const totalsFailed = items.find((i) => i.item === 'TOTALS' && i.status !== 'SUCCESS')
  let text: string
  if (total != null) {
    text = `${spaced(platform)}统计 ${total} 题`
    if (total === 0 && collected === 0) text += '，平台确认暂无通过记录'
    else if (collected === 0) text += '，尚未获取逐题明细'
    else if (timed === 0) text += `，已收录 ${collected} 道题目明细，都没有通过时间`
    else if (timed === collected) text += `，已收录 ${collected} 道明细，都有通过时间`
    else text += `，已收录 ${collected} 道明细，其中 ${timed} 道有通过时间`
    // 明细比平台数字多是口径不同：牛客 Tracker 题与普通题分开计，洛谷的统计不含团队题、用户题
    const tracker = account.trackerProblems ?? 0
    if (collected > total && tracker > 0) text += `；其中 ${tracker} 道只在 Tracker 通过，平台统计不含`
    else if (collected > total && account.platform === 'LUOGU') text += '；明细多于平台统计：洛谷的统计不含团队题、用户题'
    else if (collected > total) text += '；明细多于平台统计：两边计数口径不同'
    if (totalsFailed) text += `（统计为 ${beijingTime(account.platformSolvedAt)} 的数据，最近一次获取失败）`
    return text
  }
  if (collected > 0) {
    return timed === collected
      ? `已收录 ${collected} 道通过题目，都有通过时间`
      : `已收录 ${collected} 道通过题目，其中 ${timed} 道有通过时间`
  }
  if (items.some((i) => i.status !== 'SUCCESS')) return '尚未取得可确认的数据：最近一次获取失败，不代表没有刷题'
  if (items.length) return `${spaced(platform)}确认暂无通过记录`
  return '还没有同步过，不代表没有刷题'
}

/**
 * 首页卡片用的一句补充：数字行已经写了平台统计 / 明细 / 有时间，这里只说数字说不清的——
 * 平台确认是零、还没有逐题明细、明细比平台少或多的原因、统计过期、还没取到数据。没有要补的返回 null。
 */
export function dataNote(account: PlatformAccount): string | null {
  const total = account.platformSolvedTotal
  const collected = account.collectedProblems ?? 0
  const items = account.dataItems ?? []
  const totalsFailed = items.find((i) => i.item === 'TOTALS' && i.status !== 'SUCCESS')
  if (total != null) {
    const notes: string[] = []
    if (total === 0 && collected === 0) notes.push('平台确认暂无通过记录')
    else if (collected === 0) notes.push('尚未获取逐题明细')
    else if (collected < total) notes.push(`另有 ${total - collected} 题平台没有给出逐题明细`)
    const tracker = account.trackerProblems ?? 0
    if (collected > total && tracker > 0) notes.push(`其中 ${tracker} 道只在 Tracker 通过，平台统计不含`)
    else if (collected > total && account.platform === 'LUOGU') notes.push('明细多于平台统计：洛谷的统计不含团队题、用户题')
    else if (collected > total) notes.push('明细多于平台统计：两边计数口径不同')
    if (totalsFailed) notes.push(`平台统计是 ${beijingTime(account.platformSolvedAt)} 的数据，最近一次获取失败`)
    return notes.length ? notes.join('；') : null
  }
  if (collected > 0) return null
  if (items.some((i) => i.status !== 'SUCCESS')) return '尚未取得可确认的数据：最近一次获取失败，不代表没有刷题'
  if (items.length) return '平台确认暂无通过记录'
  return '还没有同步过，不代表没有刷题'
}

/** 牛客 Tracker 去重说明：同一题（题号与标题都一致）只计一次，题号撞了但标题不同的另记。 */
export function trackerNote(account: PlatformAccount): string | null {
  if (account.trackerAccepted == null) return null
  const merged = account.trackerMerged ?? 0
  const separate = account.trackerProblems ?? 0
  const conflicts = account.trackerConflicts ?? 0
  let text = `Tracker 通过 ${account.trackerAccepted} 道：${merged} 道与竞赛题是同一题（题号与标题都一致），已合并不重复计`
  if (separate) text += `；另 ${separate} 道只记在 Tracker`
  if (conflicts) text += `（其中 ${conflicts} 道题号相同但标题不同，另记未合并）`
  return text
}

/** "刚刚 / 12 分钟前 / 3 小时前 / 2 天前"，一周以上写日期；悬停时再给完整的北京时间。 */
export function relativeTime(value: string | null | undefined, now: number = Date.now()): string {
  if (!value) return '尚未成功'
  const minutes = Math.floor(Math.max(0, now - new Date(value).getTime()) / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days} 天前`
  return new Date(value).toLocaleDateString('zh-CN', { timeZone: 'Asia/Shanghai', month: 'numeric', day: 'numeric' })
}

const hourMinute = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })

/** 北京时间的「时:分」，如 "09:05"（按天分组的列表里，日期已在分组标题上） */
export function beijingClock(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : hourMinute.format(date)
}

/** "今天 18:10 / 昨天 21:36 / 9/25 22:05"，不是今年的只写日期 "2025/12/3"（都按北京时间）。 */
export function dayTime(value: string | null | undefined, now: number = Date.now()): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const day = campusDate(date)
  const today = campusDate(new Date(now))
  const [year, month, dayOfMonth] = day.split('-').map(Number)
  if (day.slice(0, 4) !== today.slice(0, 4)) return `${year}/${month}/${dayOfMonth}`
  const time = hourMinute.format(date)
  if (day === today) return `今天 ${time}`
  if (day === shiftDate(today, -1)) return `昨天 ${time}`
  return `${month}/${dayOfMonth} ${time}`
}

export function beijingTime(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '尚未成功同步'
}

/** 错误类型的中文名（原因说明另有 errorMsg） */
export function errorKindLabel(kind: string | null | undefined): string {
  if (!kind) return ''
  return ({ VALIDATION: '参数有误', UNAUTHENTICATED: '未登录', FORBIDDEN: '无权限', NOT_FOUND: '找不到', CONFLICT: '冲突',
    CREDENTIAL_INVALID: '登录态失效', RATE_LIMITED: '被限流', NETWORK: '网络问题', UPSTREAM_ERROR: '源站异常',
    PARSE_ERROR: '格式变化', UNSUPPORTED: '不支持', INTERNAL: '内部错误' } as Record<string, string>)[kind] ?? kind
}

/** 同步任务类型的中文名 */
export function taskTypeLabel(type: string): string {
  return ({ ACCOUNT_SYNC: '账号同步', CONTEST_REFRESH: '比赛列表刷新', PROBLEM_CATALOG: '题库同步' } as Record<string, string>)[type] ?? type
}

/** 账号同步状态的中文名（只给原始状态码时用；有账号对象时用 syncLabel） */
export function syncStatusText(status: string): string {
  return ({ OK: '更新成功', PARTIAL: '部分成功', FAILED: '更新失败', PAUSED: '已暂停', QUEUED: '排队中', PENDING: '排队中',
    RUNNING: '正在同步', SYNCING: '正在同步', IDLE: '等待首次同步' } as Record<string, string>)[status] ?? status
}

export function taskStatusLabel(status: string): string {
  return ({ PENDING: '等待执行', RUNNING: '正在同步', SUCCESS: '同步成功', FAILED: '同步失败', CANCELLED: '已取消' } as Record<string, string>)[status] ?? '等待更新'
}
