import { defineStore } from 'pinia'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { get, post } from '@/api/client'
import type { PlatformAccount } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { updatedAccount } from '@/utils/statistics'

/** 这些状态表示任务还在排队或进行中：轮询快一些，好及时看到结果 */
const ACTIVE_STATUSES = ['PENDING', 'QUEUED', 'RUNNING', 'SYNCING']
const FAST_INTERVAL = 5_000
const SLOW_INTERVAL = 30_000

export interface SyncAllResult {
  total: number
  queued: number
  alreadyQueued: number
  accounts: { accountId: number; platform: string; handle: string; status: string }[]
}

export function isSyncActive(account: PlatformAccount) {
  return ACTIVE_STATUSES.includes(account.syncStatus)
}

/** 需要本人处理：同步失败或暂停、归属不一致、待认领（还没验证归属）、登录态已失效 */
export function needsAttention(account: PlatformAccount) {
  return (
    account.syncStatus === 'FAILED' ||
    account.syncStatus === 'PAUSED' ||
    account.ownershipStatus === 'MISMATCH' ||
    account.bindingKind === 'CLAIMABLE' ||
    account.credentialStatus === 'INVALID'
  )
}

/**
 * 当前用户的平台账号：全站只在这里轮询 /accounts。
 * 组件挂载期间用 useAccounts() 订阅；没人订阅或页面不可见时停止，回到页面时数据过期就立刻刷新。
 * 有账号在排队或同步中时 5 秒一次，否则 30 秒一次；换了登录用户就清空，不把上一个人的账号显示出来。
 */
export const useAccountsStore = defineStore('accounts', () => {
  const auth = useAuthStore()
  const accounts = ref<PlatformAccount[]>([])
  const loaded = ref(false)
  const error = ref('')
  const lastLoadedAt = ref<number | null>(null)
  const syncingAll = ref(false)

  const syncing = computed(() => accounts.value.some(isSyncActive))
  const pendingCount = computed(() => accounts.value.filter(needsAttention).length)
  const updatedCount = computed(() => accounts.value.filter(updatedAccount).length)
  /** 正常：最近更新成功、也没有要处理的事（和 pendingCount 不重叠） */
  const healthyCount = computed(() => accounts.value.filter((account) => updatedAccount(account) && !needsAttention(account)).length)
  /** 所有账号里最近一次成功更新的时间 */
  const lastSuccessAt = computed(() =>
    accounts.value.reduce<string | null>(
      (latest, account) =>
        account.lastSuccessAt && (!latest || Date.parse(account.lastSuccessAt) > Date.parse(latest))
          ? account.lastSuccessAt
          : latest,
      null
    )
  )

  let subscribers = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  let inflight: Promise<boolean> | null = null
  /** 换用户时加一，丢掉换之前发出的请求的结果 */
  let generation = 0

  const interval = () => (syncing.value ? FAST_INTERVAL : SLOW_INTERVAL)
  const stale = () => lastLoadedAt.value === null || Date.now() - lastLoadedAt.value >= FAST_INTERVAL

  function schedule() {
    clearTimeout(timer)
    timer = undefined
    if (!subscribers || !auth.isLoggedIn || document.hidden) return
    timer = setTimeout(() => void refresh(), interval())
  }

  /** 读一次账号列表；已经有请求在路上就等它，不重复发；没登录时不读。读到了返回 true */
  function refresh(): Promise<boolean> {
    if (inflight) return inflight
    if (!auth.isLoggedIn) return Promise.resolve(false)
    const request = generation
    inflight = get<PlatformAccount[]>('/accounts')
      .then(
        (list) => {
          if (request !== generation) return false
          accounts.value = list.filter((account) => account.platform !== 'LANQIAO')
          loaded.value = true
          error.value = ''
          lastLoadedAt.value = Date.now()
          return true
        },
        () => {
          if (request === generation) error.value = '平台状态加载失败，已显示的数据可能过期。'
          return false
        }
      )
      .finally(() => {
        if (request !== generation) return
        inflight = null
        schedule()
      })
    return inflight
  }

  /** 改了账号状态之后用：等路上那个请求（它可能在改动之前发出）结束，再读一次。读到了返回 true */
  async function reload() {
    if (inflight) await inflight
    return refresh()
  }

  function onVisibilityChange() {
    if (document.hidden) schedule()
    else if (stale()) void refresh()
    else schedule()
  }

  /** 开始订阅；返回取消订阅的函数。第一个订阅者到来时数据过期就立刻读一次 */
  function subscribe() {
    subscribers++
    if (subscribers === 1) document.addEventListener('visibilitychange', onVisibilityChange)
    if (stale()) void refresh()
    else if (!inflight) schedule()
    let active = true
    return () => {
      if (!active) return
      active = false
      subscribers--
      if (subscribers) return
      document.removeEventListener('visibilitychange', onVisibilityChange)
      clearTimeout(timer)
      timer = undefined
    }
  }

  async function syncAccount(id: number) {
    await post(`/accounts/${id}/sync`)
    await reload()
  }

  /** 把所有账号加入同步队列；之后账号处于排队 / 同步中，轮询自动变快，直到都结束 */
  async function syncAll() {
    if (syncingAll.value) return null
    syncingAll.value = true
    try {
      const result = await post<SyncAllResult>('/accounts/sync-all')
      await reload()
      return result
    } finally {
      syncingAll.value = false
    }
  }

  watch(
    () => auth.me?.id,
    () => {
      generation++
      inflight = null
      clearTimeout(timer)
      timer = undefined
      accounts.value = []
      loaded.value = false
      error.value = ''
      lastLoadedAt.value = null
      if (subscribers && auth.isLoggedIn) void refresh()
    }
  )

  return {
    accounts,
    loaded,
    error,
    lastLoadedAt,
    syncing,
    syncingAll,
    pendingCount,
    updatedCount,
    healthyCount,
    lastSuccessAt,
    refresh,
    reload,
    subscribe,
    syncAccount,
    syncAll
  }
})

/** 在组件里用：挂载期间保持轮询 */
export function useAccounts(enabled = true) {
  const store = useAccountsStore()
  let unsubscribe: (() => void) | undefined
  onMounted(() => {
    if (enabled) unsubscribe = store.subscribe()
  })
  onBeforeUnmount(() => unsubscribe?.())
  return store
}
