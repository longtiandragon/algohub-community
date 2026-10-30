import type { PlatformAccount } from '@/api/types'
import { isSyncActive } from '@/stores/accounts'
import { DATA_ITEM_LABEL, beijingTime, passedCount, relativeTime } from '@/utils/statistics'

/**
 * 平台账号页上一个账号的状态怎么说：卡片的状态行、红色浅底的原因、三格数字、补充说明，
 * 以及「需要你处理」里的那几类事。先说要处理的（归属不一致、登录态失效、失败、暂停），
 * 再说进行中、没同步过，最后是正常。
 */
export type AccountTone = 'ok' | 'busy' | 'idle' | 'warn' | 'bad'

/** 圆点与文字的颜色（UnoCSS 类） */
export const TONE_DOT: Record<AccountTone, string> = { ok: 'bg-success', busy: 'bg-brand-400', idle: 'bg-faint', warn: 'bg-warning', bad: 'bg-danger' }
export const TONE_INK: Record<AccountTone, string> = { ok: 'text-body', busy: 'text-body', idle: 'text-muted', warn: 'text-warning-ink', bad: 'text-danger-ink' }

/** 绑定方式的标签（StatusBadge 的 label 样式） */
export const BINDING_KIND: Record<string, { label: string; tone: 'success' | 'warning' | 'neutral' }> = {
  VERIFIED: { label: '正式绑定', tone: 'success' },
  CLAIMABLE: { label: '待认领', tone: 'warning' },
  WATCHING: { label: '个人关注', tone: 'neutral' }
}

export function bindingKind(account: PlatformAccount) {
  return BINDING_KIND[account.bindingKind ?? ''] ?? { label: account.ownershipStatus, tone: 'neutral' as const }
}

function since(account: PlatformAccount) {
  return account.lastSuccessAt ? `上次成功 ${relativeTime(account.lastSuccessAt)}` : '还没成功过'
}

/** 同步必须要登录态、却还没设置过（如刚验证完归属的蓝桥云课）：后端因此暂停了同步 */
function needsFirstCredential(account: PlatformAccount) {
  return !account.hasCredential && account.syncStatus === 'PAUSED' && account.lastErrorKind === 'CREDENTIAL_INVALID'
}

/** 状态行：「同步正常 · 12 分钟前」「同步失败 · 上次成功 3 小时前」 */
export function accountStatus(account: PlatformAccount): { tone: AccountTone; text: string } {
  if (account.ownershipStatus === 'MISMATCH') return { tone: 'bad', text: '账号归属不一致 · 已停止同步' }
  if (account.authMode === 'BROWSER') return account.lastSuccessAt
    ? { tone: 'ok', text: `插件同步成功 · ${relativeTime(account.lastSuccessAt)}` }
    : { tone: 'idle', text: '等待连接浏览器插件' }
  if (account.credentialStatus === 'INVALID') return { tone: 'warn', text: `登录态已失效 · ${since(account)}` }
  if (needsFirstCredential(account)) return { tone: 'warn', text: '还没设置登录态 · 暂不同步' }
  if (account.syncStatus === 'FAILED') return { tone: 'bad', text: `同步失败 · ${since(account)}` }
  if (account.syncStatus === 'PAUSED') return { tone: 'warn', text: `同步已暂停 · ${since(account)}` }
  if (account.syncStatus === 'RETRY') return { tone: 'warn', text: `等待重试 · ${since(account)}` }
  if (isSyncActive(account)) {
    return { tone: 'busy', text: ['RUNNING', 'SYNCING'].includes(account.syncStatus) ? '正在同步…' : '排队中，马上开始同步' }
  }
  if (account.platform === 'LUOGU' && account.ownershipStatus === 'VERIFIED' && !account.historyComplete && !account.timedProblems) {
    return { tone: 'warn', text: '累计题数已计入 · 浏览器同步可补通过时间' }
  }
  if (!account.lastSuccessAt) return { tone: 'idle', text: '等待首次同步' }
  const when = relativeTime(account.lastSuccessAt)
  if (account.bindingKind === 'CLAIMABLE') return { tone: 'warn', text: `同步正常 · ${when} · 还没验证归属` }
  if (account.syncStatus === 'PARTIAL') return { tone: 'warn', text: `部分更新成功 · ${when}` }
  return { tone: 'ok', text: `同步正常 · ${when}` }
}

/**
 * 出问题时的原因（卡片上红色浅底那段）。各数据项写了原因就逐项列出；都没写时用账号级的错误，
 * 免得同一句话出现两遍。同步进行中不显示上一轮的错误。
 */
export function accountProblem(account: PlatformAccount): string | null {
  if (account.ownershipStatus === 'MISMATCH') return account.lastErrorMsg || '平台上这个账号的标识和绑定时的不一致，已停止同步。'
  if (account.authMode === 'BROWSER') return null
  if (account.credentialStatus === 'INVALID') return '保存的登录态已经失效，同步用不了；在「⋯」里更新登录态后继续同步。'
  if (needsFirstCredential(account)) return '这个平台的做题记录要登录才能看：在「⋯」→「设置登录态」里填好后开始同步。'
  if (account.syncStatus === 'OK' || isSyncActive(account)) return null
  const failed = (account.dataItems ?? []).filter((item) => item.status !== 'SUCCESS' && item.errorMsg)
  if (failed.length) return failed.map((item) => `${DATA_ITEM_LABEL[item.item] ?? item.item}：${item.errorMsg}`).join('；')
  if (!account.lastErrorKind) return null
  return account.lastErrorMsg || '更新未完成，请重试或检查授权'
}

export interface AccountFact {
  label: string
  value: string
  muted?: boolean
}

/**
 * 卡片上的数字：只写「已通过」一个数（平台统计和已收录明细取大的，见 passedCount），有 Rating 的平台再写 Rating。
 * 已收录明细、有没有通过时间这些细节放在「详情」里，卡片上不再分三格。没取得的写「—」，不写成 0。
 */
export function accountFacts(account: PlatformAccount): AccountFact[] {
  const passed = passedCount(account)
  const facts: AccountFact[] = [passed != null ? { label: '已通过', value: `${passed} 题` } : { label: '已通过', value: '—', muted: true }]
  if (account.currentRating != null) facts.push({ label: 'Rating', value: `${account.currentRating}` })
  else if ((account.dataItems ?? []).some((item) => item.item === 'RATING')) facts.push({ label: 'Rating', value: '—', muted: true })
  return facts
}

/** 补充一行小字：只在「已通过」这个数可能过时的时候说——平台统计最近一次没取到，用的是之前的数 */
export function accountNote(account: PlatformAccount): string | null {
  const totalsFailed = (account.dataItems ?? []).some((item) => item.item === 'TOTALS' && item.status !== 'SUCCESS')
  if (totalsFailed && account.platformSolvedTotal != null && account.platformSolvedTotal >= (account.collectedProblems ?? 0)) {
    return `题数是 ${beijingTime(account.platformSolvedAt)} 取到的，最近一次没取到，同步成功后会更新`
  }
  return null
}
