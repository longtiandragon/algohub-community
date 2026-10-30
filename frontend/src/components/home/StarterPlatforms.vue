<script setup lang="ts">
import PlatformLogo from '@/components/PlatformLogo.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import { bindingGuides } from '@/utils/bindingGuides'

/** 新同学「从常用平台开始」：6 个常用平台，点了带着平台去平台账号页，直接打开添加向导（/accounts?add=CODEFORCES）。 */
const PLATFORMS = [
  { platform: 'LUOGU', name: '洛谷' },
  { platform: 'LEETCODE_CN', name: '力扣' },
  { platform: 'CODEFORCES', name: 'Codeforces' },
  { platform: 'ATCODER', name: 'AtCoder' },
  { platform: 'NOWCODER', name: '牛客' },
  { platform: 'MATIJI', name: '码蹄集' }
].map((item) => ({ ...item, hint: bindingGuides[item.platform]?.hint ?? '' }))
</script>

<template>
  <SectionCard title="从常用平台开始">
    <template #actions><RouterLink to="/accounts">全部平台 →</RouterLink></template>
    <div class="grid grid-cols-3 gap-2.5">
      <RouterLink
        v-for="item in PLATFORMS"
        :key="item.platform"
        :to="{ path: '/accounts', query: { add: item.platform } }"
        class="platform-tile"
        :aria-label="`添加${item.name}账号（${item.hint}）`"
      >
        <PlatformLogo :platform="item.platform" :size="34" />
        <span class="text-[13.5px] text-ink font-semibold">{{ item.name }}</span>
        <span class="text-[11.5px] text-muted">{{ item.hint }}</span>
      </RouterLink>
    </div>
    <p class="m-0 mt-3 text-[12.5px] text-muted">先绑你已经在刷题的平台，之后随时可以再加。</p>
  </SectionCard>
</template>

<style scoped>
.platform-tile {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  min-width: 0;
  padding: 14px 6px 12px;
  border: 1px solid var(--border);
  border-radius: 12px;
  text-align: center;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}

.platform-tile:hover {
  border-color: var(--brand-400);
  background: color-mix(in srgb, var(--brand-soft) 50%, var(--bg-elevated));
  text-decoration: none;
}

.platform-tile:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}
</style>
