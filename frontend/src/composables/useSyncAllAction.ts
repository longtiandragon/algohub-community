import { ElMessage } from 'element-plus'
import { notifyError } from '@/api/client'
import { useAccountsStore } from '@/stores/accounts'

/** 「同步全部」按钮：提交后按结果给一句提示。侧栏同步卡片、总览页用它。 */
export function useSyncAllAction() {
  const store = useAccountsStore()
  return async function syncAll() {
    try {
      const result = await store.syncAll()
      if (!result) return
      if (!result.total) ElMessage.info('请先绑定至少一个平台账号')
      else if (result.queued) ElMessage.success(`已开始同步 ${result.queued} 个账号`)
      else ElMessage.info('账号都已在同步队列里')
    } catch (e) {
      notifyError(e)
    }
  }
}
