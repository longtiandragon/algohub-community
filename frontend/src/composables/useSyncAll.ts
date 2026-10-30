import { onBeforeUnmount, ref, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import { notifyError, post } from '@/api/client'
import type { SyncTask } from '@/api/types'
import type { SyncAllResult } from '@/stores/accounts'

/**
 * 平台账号页的「一键同步全部 OJ」：提交后每 2.5 秒刷新一次任务列表，
 * 直到这一轮每个账号最新的任务都结束（成功或失败），最多跟 90 秒；到时间还没确认完就停下，
 * 说明「尚未确认」，后台任务照常执行。读不到任务列表时不下结论（「正在重试」）；
 * 页面离开后不再提示、不再排下一次。（总览、侧栏的「同步全部」只提交、不跟进度，见 useSyncAllAction。）
 */
export function useSyncAll(options: {
  /** 页面的刷新：读到了返回 true */
  load: (options?: { silent?: boolean }) => Promise<boolean>
  tasks: Ref<SyncTask[]>
  hasAccounts: () => boolean
}) {
  const syncingAll = ref(false)
  const syncProgress = ref('')
  const syncAllResult = ref<SyncAllResult | null>(null)
  let timer: ReturnType<typeof setTimeout> | undefined
  let deadline = 0
  let disposed = false
  onBeforeUnmount(() => {
    disposed = true
    clearTimeout(timer)
  })

  /** 跟踪到什么时候为止（毫秒时间戳） */
  function followUntil(time: number) {
    deadline = time
  }

  async function pollSyncAll() {
    clearTimeout(timer)
    const loaded = await options.load({ silent: true })
    if (disposed) return
    const ids = new Set(syncAllResult.value?.accounts.map((row) => row.accountId) ?? [])
    const latest = new Map<number, SyncTask>()
    for (const task of options.tasks.value) {
      if (task.accountId != null && ids.has(task.accountId) && !latest.has(task.accountId)) latest.set(task.accountId, task)
    }
    const current = [...latest.values()]
    const finished = current.filter((task) => task.status === 'SUCCESS' || task.status === 'FAILED')
    if (loaded && ids.size > 0 && finished.length === ids.size) {
      syncingAll.value = false
      const failed = current.filter((task) => task.status === 'FAILED').length
      syncProgress.value = failed ? `本轮同步已结束，${failed} 个平台需要处理错误` : '全部同步任务已完成，刷题量已更新'
      if (failed) ElMessage.warning(syncProgress.value)
      else ElMessage.success(syncProgress.value)
      return
    }
    syncProgress.value = loaded
      ? `已确认结束 ${finished.length} / ${ids.size} 个平台；其余状态仍在查询。`
      : '暂时无法读取同步进度，正在重试；不能据此判断同步是否完成。'
    if (Date.now() >= deadline) {
      syncingAll.value = false
      syncProgress.value = '自动查询已结束，部分平台的完成状态尚未确认，请稍后刷新。后台任务会继续执行。'
      ElMessage.info(syncProgress.value)
      return
    }
    timer = setTimeout(pollSyncAll, 2500)
  }

  async function onSyncAll() {
    if (syncingAll.value || !options.hasAccounts()) return
    syncingAll.value = true
    syncProgress.value = '正在提交同步任务…'
    try {
      const result = await post<SyncAllResult>('/accounts/sync-all')
      syncAllResult.value = result
      if (disposed) return
      if (!result.total) {
        ElMessage.info('请先绑定至少一个 OJ 账号')
        syncingAll.value = false
        return
      }
      ElMessage.success(`已处理 ${result.total} 个平台：新排队 ${result.queued} 个，已有任务 ${result.alreadyQueued} 个`)
      followUntil(Date.now() + 90_000)
      await pollSyncAll()
    } catch (e) {
      syncingAll.value = false
      syncProgress.value = '同步请求未确认成功，请刷新任务列表后重试。'
      if (!disposed) notifyError(e)
    }
  }

  return { syncingAll, syncProgress, syncAllResult, onSyncAll, pollSyncAll, followUntil }
}
