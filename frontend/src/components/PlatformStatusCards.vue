<script setup lang="ts">
import { computed } from 'vue'
import PlatformLogo from './PlatformLogo.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import { useAccounts } from '@/stores/accounts'
import { accountBrief } from '@/utils/accountBrief'
import { passedCount } from '@/utils/statistics'
import { platformName } from '@/utils/platformBrand'

const store = useAccounts()
const cards = computed(() => store.accounts.map(account => {
  const brief = accountBrief(account)
  const healthy = !!account.lastSuccessAt && !['bad', 'warn'].includes(brief.tone)
  return { account, healthy, tip: `${healthy ? '同步正常' : '同步异常'} · ${brief.text}` }
}))
</script>

<template>
  <SectionCard title="平台账号" data-test="home-platform-accounts">
    <template #actions><RouterLink to="/accounts">管理账号 →</RouterLink></template>
    <div v-if="!store.loaded && !store.error" class="account-grid" aria-busy="true">
      <Skeleton v-for="n in 4" :key="n" class="h-16 rounded-control" />
    </div>
    <div v-else-if="store.error && !store.loaded">
      <p class="text-sm text-danger-ink">{{ store.error }}</p>
      <el-button size="small" @click="store.refresh()">重试</el-button>
    </div>
    <EmptyState v-else-if="!store.accounts.length" icon="i-lucide-link" title="还没有绑定平台账号" description="绑定后自动同步通过记录" action="去绑定" to="/accounts" />
    <ul v-else class="account-grid">
      <li v-for="{ account, healthy, tip } in cards" :key="account.id" class="account-card">
        <PlatformLogo :platform="account.platform" :size="28" :title="platformName(account.platform)" />
        <RouterLink to="/accounts" class="account-info" :title="`${platformName(account.platform)} · ${account.handle}`">
          <span class="mono handle">{{ account.handle }}</span>
          <span class="count"><strong class="num">{{ passedCount(account) ?? '—' }}</strong> 题</span>
        </RouterLink>
        <el-tooltip :content="tip" placement="top">
          <button type="button" class="sync-dot" :class="healthy ? 'is-healthy' : 'is-unhealthy'" :aria-label="tip" />
        </el-tooltip>
      </li>
    </ul>
  </SectionCard>
</template>

<style scoped>
.account-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin: 0; padding: 0; list-style: none; }
.account-card { display: flex; align-items: center; gap: 9px; min-width: 0; padding: 10px; border: 1px solid var(--border); border-radius: 10px; }
.account-info { display: flex; flex: 1; flex-direction: column; gap: 3px; min-width: 0; text-decoration: none; }
.handle { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text-strong); font-size: 12px; }
.count { color: var(--text-muted); font-size: 11px; }
.count strong { color: var(--text-strong); font-size: 15px; }
.sync-dot { flex-shrink: 0; width: 9px; height: 9px; padding: 0; border: 0; border-radius: 50%; cursor: help; }
.sync-dot:focus-visible { outline: 2px solid var(--brand-500); outline-offset: 3px; }
.is-healthy { background: var(--success); }
.is-unhealthy { background: var(--danger); }
@media (min-width: 768px) { .account-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (min-width: 1200px) { .account-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); } }
</style>
