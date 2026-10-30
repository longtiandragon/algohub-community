import type { PlatformAccount } from '@/api/types'
import { isSyncActive } from '@/stores/accounts'
import { passedCount, relativeTime } from '@/utils/statistics'

export type BriefTone = 'ok' | 'busy' | 'idle' | 'warn' | 'bad'

export interface AccountBrief {
  tone: BriefTone
  /** 状态一句话：「同步正常 · 12 分钟前」「还没验证归属」 */
  text: string
  /** 补充一句：「已通过 186 题」「暂不进校内排行」 */
  fact: string
  /** 要处理时给一个动作（都去平台账号页） */
  action?: string
}

/**
 * 总览等处的账号一句话状态（数据项明细在平台账号页）。
 * 先说要处理的事：归属不一致、登录态失效、同步失败、暂停、还没验证归属；其次是同步中、没同步过、正常。
 */
export function accountBrief(account: PlatformAccount): AccountBrief {
  // 和平台账号页同一个数：平台统计和已收录明细取大的
  const passed = passedCount(account)
  const collected = passed != null ? `已通过 ${passed} 题` : ''
  if (account.ownershipStatus === 'MISMATCH') return { tone: 'bad', text: '账号归属不一致', fact: '已停止同步', action: '去处理' }
  if (account.credentialStatus === 'INVALID') return { tone: 'warn', text: '登录态已失效', fact: '更新后继续同步', action: '去更新' }
  if (account.syncStatus === 'FAILED') {
    return {
      tone: 'bad',
      text: '同步失败 · 稍后自动重试',
      fact: account.lastSuccessAt ? `上次成功 ${relativeTime(account.lastSuccessAt)}` : '还没同步成功过',
      action: '看原因'
    }
  }
  if (account.syncStatus === 'PAUSED') return { tone: 'warn', text: '同步已暂停', fact: collected, action: '去处理' }
  if (account.bindingKind === 'CLAIMABLE') return { tone: 'warn', text: '还没验证归属', fact: '暂不进校内排行', action: '去验证' }
  if (isSyncActive(account)) return { tone: 'busy', text: '正在同步…', fact: collected }
  if (!account.lastSuccessAt) return { tone: 'idle', text: '等待首次同步', fact: '通常 1–3 分钟' }
  return { tone: 'ok', text: `同步正常 · ${relativeTime(account.lastSuccessAt)}`, fact: collected }
}
