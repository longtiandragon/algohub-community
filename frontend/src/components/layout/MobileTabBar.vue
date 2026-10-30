<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useRoute, useRouter } from 'vue-router'
import { pendingNavigation, prefetchPage } from '@/composables/useNavigation'
import { useAccounts } from '@/stores/accounts'
import { ADMIN_NAV_ITEMS, TAB_ITEMS, isTabActive } from './nav'

/**
 * 手机（< 768）底栏：总览 · 排行 · 档案 · 账号 · 我的，固定在底部，留出 iPhone 底部安全区。
 * 「账号」有待处理时右上角一个琥珀色圆点。页面内容区在手机上底部多留了底栏的高度，不会被挡住。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const accounts = useAccounts(!auth.isAdmin)
const items = computed(() => auth.isAdmin ? ADMIN_NAV_ITEMS : TAB_ITEMS)
</script>

<template>
  <nav aria-label="底部导航" class="mobile-tabbar" :style="{ gridTemplateColumns: `repeat(${items.length}, minmax(0, 1fr))` }">
    <RouterLink
      v-for="item in items"
      :key="item.path"
      :to="item.path"
      @pointerenter="prefetchPage(router, item.path)"
      @focus="prefetchPage(router, item.path)"
      @touchstart.passive="prefetchPage(router, item.path)"
      class="tab"
      :class="{ 'is-active': isTabActive(item, pendingNavigation?.path ?? route.path, pendingNavigation ? pendingNavigation.meta.mobileParent : route.meta.mobileParent) }"
      :aria-current="isTabActive(item, pendingNavigation?.path ?? route.path, pendingNavigation ? pendingNavigation.meta.mobileParent : route.meta.mobileParent) ? 'page' : undefined"
      :aria-label="item.pending && accounts.pendingCount ? `${item.label}，${accounts.pendingCount} 个待处理` : undefined"
    >
      <span :class="item.icon" class="text-[23px]" aria-hidden="true" />
      {{ item.short }}
      <span v-if="item.pending && accounts.pendingCount" class="tab-dot" aria-hidden="true" />
    </RouterLink>
  </nav>
</template>

<style scoped>
.mobile-tabbar {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 20;
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  padding: 6px calc(6px + env(safe-area-inset-right)) calc(6px + env(safe-area-inset-bottom)) calc(6px + env(safe-area-inset-left));
  border-top: 1px solid var(--border);
  background: color-mix(in srgb, var(--bg-elevated) 96%, transparent);
  backdrop-filter: saturate(1.8) blur(12px);
}

.tab {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  height: 52px;
  border-radius: 12px;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1;
  text-decoration: none;
  -webkit-tap-highlight-color: transparent;
}

.tab:hover {
  color: var(--text-strong);
  text-decoration: none;
}

.tab.is-active {
  color: var(--brand-500);
  font-weight: 600;
}

.tab:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: -2px;
}

.tab-dot {
  position: absolute;
  top: 4px;
  left: 50%;
  box-sizing: content-box;
  width: 9px;
  height: 9px;
  margin-left: 6px;
  border: 2px solid var(--bg-elevated);
  border-radius: 50%;
  background: var(--warning);
}
</style>
