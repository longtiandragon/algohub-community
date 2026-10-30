import type { BindingRequest, OwnershipAppeal, PlatformAccount } from '@/api/types'
import { needsAttention } from '@/stores/accounts'
import { platformName } from '@/utils/platformBrand'
import { accountProblem } from './accountState'

/**
 * 「需要你处理」里的一件事。四类：账号出了问题（归属不一致、登录态失效、同步失败或暂停）、还没验证归属、
 * 待验证的绑定申请、审核中的归属申诉。每件事一个主要动作，申请和申诉另有「取消申请」「撤回」。
 */
export type TodoKind = 'mismatch' | 'credential' | 'failed' | 'paused' | 'verify' | 'request' | 'appeal'

export interface Todo {
  key: string
  kind: TodoKind
  platform: string
  title: string
  sub: string
  account?: PlatformAccount
  request?: BindingRequest
  appeal?: OwnershipAppeal
}

function minutesLeft(expiresAt: string, now: number) {
  const minutes = Math.floor((Date.parse(expiresAt) - now) / 60000)
  return minutes >= 1 ? `还剩 ${minutes} 分钟` : '不到 1 分钟就过期'
}

function dateOf(value: string) {
  return new Date(value).toLocaleDateString('zh-CN', { timeZone: 'Asia/Shanghai', month: 'numeric', day: 'numeric' })
}

/** 先说出了问题的账号，再说还没验证归属的，然后是申请和申诉 */
export function buildTodos(
  accounts: PlatformAccount[],
  requests: BindingRequest[],
  appeals: OwnershipAppeal[],
  now: number = Date.now()
): Todo[] {
  const problems: Todo[] = []
  const verify: Todo[] = []
  for (const account of accounts) {
    if (!needsAttention(account)) continue
    const name = `${platformName(account.platform)} · ${account.handle}`
    const base = { platform: account.platform, account }
    const kept = account.lastSuccessAt ? '；已保留上次同步到的数据' : ''
    if (account.ownershipStatus === 'MISMATCH') {
      problems.push({ ...base, key: `mismatch-${account.id}`, kind: 'mismatch', title: `${name} 账号归属不一致`, sub: accountProblem(account) ?? '' })
    } else if (account.credentialStatus === 'INVALID') {
      problems.push({ ...base, key: `credential-${account.id}`, kind: 'credential', title: `${name} 登录态已失效`, sub: `更新登录态后才能继续同步${kept}` })
    } else if (account.syncStatus === 'FAILED') {
      problems.push({ ...base, key: `failed-${account.id}`, kind: 'failed', title: `${name} 同步失败`, sub: `${accountProblem(account) ?? '最近一次同步没有成功'}${kept}` })
    } else if (account.syncStatus === 'PAUSED') {
      problems.push({ ...base, key: `paused-${account.id}`, kind: 'paused', title: `${name} 同步已暂停`, sub: accountProblem(account) ?? '自动同步已暂停，可以立即重试' })
    }
    // 经申诉转给别人的旧账号带着说明，没有可做的事，说明写在卡片上
    if (account.bindingKind === 'CLAIMABLE' && !account.ownershipNote) {
      verify.push({ ...base, key: `verify-${account.id}`, kind: 'verify', title: `${name} 还没验证归属`, sub: '验证后才会进入校内排行和动态' })
    }
  }
  const pending = requests.map<Todo>((request) => {
    // 位置以字母开头（如「Codeforces → Settings …」）时和前面的中文空一格
    const where = request.location ? request.location.replace(/^(?=[A-Za-z0-9])/, ' ') : '平台的公开资料'
    let sub = request.expired ? '验证串已过期，重新申请会签发新的' : `把验证串贴进${where}，${minutesLeft(request.expiresAt, now)}`
    if (request.ownedByOther) sub += '；这个账号已归另一位同学，核验成功后转为申诉'
    return {
      key: `request-${request.id}`,
      kind: 'request',
      platform: request.platform,
      title: `绑定申请 · ${platformName(request.platform)} ${request.handle}`,
      sub,
      request
    }
  })
  const waiting = appeals
    .filter((appeal) => appeal.status === 'PENDING')
    .map<Todo>((appeal) => ({
      key: `appeal-${appeal.id}`,
      kind: 'appeal',
      platform: appeal.platform,
      title: `归属申诉 · ${platformName(appeal.platform)} ${appeal.handle}`,
      sub: `${dateOf(appeal.createdAt)} 提交，等管理员审核${appeal.reason ? '' : '；补充说明能帮助审核'}`,
      appeal
    }))
  return [...problems, ...verify, ...pending, ...waiting]
}
