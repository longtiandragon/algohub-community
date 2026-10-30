<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { topBarOverride } from '@/composables/useTopBarOverride'
import { pendingNavigation } from '@/composables/useNavigation'

/**
 * 手机（< 768）顶栏，高 56，吸在顶上。
 * 标签页左边是页面标题（总览多一个字标）；「我的」下面的页面（动态、比赛、设置、管理）左边是返回，回到「我的」。
 * 右边是这一页最常用的操作：页面在 PageHeader 的 actions（或 mobile-actions）里写，手机上自动送到这里。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

// 同一路由里的子页（如设置的 ?section=）可以临时换标题和返回目标，见 useTopBarOverride
const title = computed(() => pendingNavigation.value
  ? pendingNavigation.value.meta.mobileTitle ?? pendingNavigation.value.meta.title ?? ''
  : topBarOverride.title ?? route.meta.mobileTitle ?? route.meta.title ?? '')
const parent = computed(() => pendingNavigation.value
  ? (auth.isAdmin ? undefined : pendingNavigation.value.meta.mobileParent)
  : topBarOverride.parent ?? (auth.isAdmin ? undefined : route.meta.mobileParent))
const parentTitle = computed(() => (!pendingNavigation.value && topBarOverride.parentTitle) || (parent.value ? router.resolve(parent.value).meta.title ?? '' : ''))
const isHome = computed(() => (pendingNavigation.value?.path ?? route.path) === '/')

function back() {
  if (parent.value) void router.push(parent.value)
}
</script>

<template>
  <header class="mobile-topbar" :class="{ 'has-back': parent }">
    <button v-if="parent" type="button" class="topbar-back" :aria-label="`返回${parentTitle}`" @click="back">
      <span class="i-lucide-chevron-left text-[22px]" aria-hidden="true" />
    </button>
    <img v-else-if="isHome" src="/algohub-mark.svg" alt="" class="size-7 shrink-0" aria-hidden="true" />
    <span class="min-w-0 flex-1 truncate text-[17px] text-ink font-semibold">{{ title }}</span>
    <div v-show="!pendingNavigation" id="mobile-topbar-actions" class="flex shrink-0 items-center gap-1.5" />
  </header>
</template>

<style scoped>
.mobile-topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 10px;
  height: calc(56px + env(safe-area-inset-top));
  /* 左右的安全区由外层 .app-shell 让开，这里只管顶部（添加到主屏幕后全屏打开时的状态栏） */
  padding: env(safe-area-inset-top) 8px 0 16px;
  border-bottom: 1px solid var(--border);
  background: color-mix(in srgb, var(--bg-elevated) 94%, transparent);
  backdrop-filter: saturate(1.8) blur(12px);
}

.mobile-topbar.has-back {
  gap: 2px;
  padding-left: 4px;
}

.topbar-back {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  padding: 0;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--text-strong);
  cursor: pointer;
}

.topbar-back:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: -2px;
}
</style>
