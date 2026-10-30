import { computed, onBeforeUnmount, ref } from 'vue'
import { get, notifyError } from '@/api/client'
import type { BindingRequest, OwnershipAppeal, PlatformCapability, SyncTask } from '@/api/types'
import { useAccountsStore } from '@/stores/accounts'

/**
 * 平台账号页的数据：账号列表来自账号 store（全站统一轮询 /accounts），
 * 平台能力、同步任务、待验证的绑定申请、我的归属申诉在这里读。
 * 一次 load 并发读完；后发的请求盖过先发的，先发的晚回来也不算数；页面离开后迟到的响应不再写进来。
 */
export function useAccountsPage() {
  const store = useAccountsStore()
  const capabilities = ref<PlatformCapability[]>([])
  const tasks = ref<SyncTask[]>([])
  const pendingBindings = ref<BindingRequest[]>([])
  const myAppeals = ref<OwnershipAppeal[]>([])
  const loading = ref(true)
  let loadRequest = 0
  let disposed = false
  onBeforeUnmount(() => {
    disposed = true
  })

  /** silent：跟踪同步进度时用，读不到不弹提示。读到了返回 true */
  async function load(options?: { silent?: boolean }) {
    if (disposed) return false
    const request = ++loadRequest
    const silent = options?.silent === true
    if (!silent) loading.value = true
    try {
      const [accountsLoaded, c, t, b, r] = await Promise.all([
        store.reload(),
        get<PlatformCapability[]>('/platforms/capabilities'),
        get<SyncTask[]>('/sync/tasks', { limit: 30 }),
        get<BindingRequest[]>('/binding-requests'),
        get<OwnershipAppeal[]>('/ownership-appeals')
      ])
      if (disposed || request !== loadRequest) return false
      capabilities.value = c
      tasks.value = t.filter((task) => task.platform !== 'LANQIAO')
      pendingBindings.value = b.filter((binding) => binding.platform !== 'LANQIAO')
      myAppeals.value = r.filter((appeal) => appeal.platform !== 'LANQIAO')
      if (!accountsLoaded) throw new Error(store.error || '账号列表读取失败')
      return true
    } catch (e) {
      if (!silent && !disposed && request === loadRequest) notifyError(e)
      return false
    } finally {
      if (!disposed && request === loadRequest) loading.value = false
    }
  }

  return {
    accounts: computed(() => store.accounts),
    capabilities,
    tasks,
    pendingBindings,
    myAppeals,
    loading,
    load
  }
}
