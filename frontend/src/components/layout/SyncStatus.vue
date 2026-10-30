<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useSyncAllAction } from '@/composables/useSyncAllAction'
import { isSyncActive, useAccounts } from '@/stores/accounts'
import { relativeTime } from '@/utils/statistics'

/** 侧栏底部的同步状态：最近一次更新、几个账号更新成功，右边一键同步全部。 */
const store = useAccounts()

// 「12 分钟前」要随时间走，半分钟重算一次
const now = ref(Date.now())
let clock: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  clock = setInterval(() => (now.value = Date.now()), 30_000)
})
onBeforeUnmount(() => clearInterval(clock))

const total = computed(() => store.accounts.length)
const busy = computed(() => store.syncingAll || store.syncing)

const DOT = { success: 'bg-success', warning: 'bg-warning', danger: 'bg-danger', brand: 'bg-brand', idle: 'bg-faint' }

const state = computed(() => {
  if (!store.loaded) {
    return store.error
      ? { dot: DOT.danger, title: '状态读取失败', detail: '稍后自动重试' }
      : { dot: DOT.idle, title: '正在读取', detail: '平台账号状态' }
  }
  if (!total.value) return { dot: DOT.idle, title: '还没有绑定账号', detail: '绑定后自动同步' }
  if (busy.value) {
    const active = store.accounts.filter(isSyncActive).length
    return { dot: DOT.brand, title: '正在同步', detail: active ? `${active} 个账号排队或同步中` : '正在提交' }
  }
  if (!store.updatedCount) {
    return { dot: DOT.warning, title: '暂时没有更新成功', detail: `${total.value} 个账号待处理` }
  }
  return {
    dot: DOT.success,
    title: '数据已更新',
    detail: `${relativeTime(store.lastSuccessAt, now.value)} · ${store.updatedCount}/${total.value} 个账号`
  }
})

const syncAll = useSyncAllAction()
</script>

<template>
  <div class="flex items-center gap-2.5 rounded-xl bg-surface-2 p-3">
    <span class="size-2 shrink-0 rounded-full" :class="state.dot" aria-hidden="true" />
    <span class="flex min-w-0 flex-1 flex-col leading-[1.4]">
      <span class="truncate text-[13px] font-semibold text-ink">{{ state.title }}</span>
      <span class="truncate text-xs text-muted" :title="store.lastSuccessAt ? '所有账号里最近一次更新成功的时间' : undefined">
        {{ state.detail }}
      </span>
    </span>
    <button
      v-if="total"
      type="button"
      class="sync-button"
      :disabled="busy"
      :aria-label="busy ? '正在同步' : '同步全部账号'"
      :title="busy ? '正在同步' : '同步全部账号'"
      @click="syncAll"
    >
      <span class="i-lucide-refresh-cw text-base" :class="{ 'animate-spin motion-reduce:animate-none': busy }" aria-hidden="true" />
    </button>
  </div>
</template>

<style scoped>
.sync-button {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-elevated);
  color: var(--brand-500);
  cursor: pointer;
}

.sync-button:hover:not(:disabled) {
  border-color: var(--border-strong);
  color: var(--brand-600);
}

/* 只有同步进行中才禁用：图标在转，颜色不变 */
.sync-button:disabled {
  cursor: progress;
}

.sync-button:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 1px;
}
</style>
