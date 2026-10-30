<script setup lang="ts">
import type { Ranking } from '@/api/types'
import PlatformLogo from '@/components/PlatformLogo.vue'
import { platformName } from '@/utils/platformBrand'
import { beijingTime, relativeTime } from '@/utils/statistics'

/** 这次统计了哪些平台：每个平台多少人参与、最近一次同步成功；下面一段口径 */
defineProps<{
  coverage: Ranking['coverage']
  /** 区间榜（近 7 天、近 30 天、近一年）只算有通过时间的记录 */
  timed: boolean
}>()
</script>

<template>
  <section class="flex flex-col gap-1.5 border border-line rounded-card border-solid bg-surface px-4 py-4 md:(px-5 py-[18px])" data-test="ranking-coverage">
    <h2 class="m-0 mb-1.5 text-[15px] text-ink font-semibold leading-6">这次统计了哪些平台</h2>
    <p v-if="!coverage.length" class="m-0 text-[13px] text-muted">当前范围里还没有参与统计的平台。</p>
    <ul v-else class="m-0 list-none p-0">
      <li v-for="item in coverage" :key="item.platform" class="flex items-center gap-2.5 py-2">
        <PlatformLogo :platform="item.platform" :size="22" />
        <span class="min-w-0 flex-1 truncate text-[13.5px] text-ink">{{ platformName(item.platform) }}</span>
        <span class="num text-[13px] text-body">{{ item.users }} 人</span>
        <span class="w-[72px] text-right text-xs text-muted" :title="item.lastSuccessAt ? `最近同步成功：${beijingTime(item.lastSuccessAt)}` : ''">
          {{ item.lastSuccessAt ? relativeTime(item.lastSuccessAt) : '未同步' }}
        </span>
      </li>
    </ul>
    <p class="m-0 mt-1.5 border-t border-t-solid border-line-soft pt-3 text-[12.5px] text-muted leading-relaxed">
      <template v-if="timed">近 7 天、近 30 天、近一年榜只算能确定首次通过时间的记录。</template>
      只统计验证过归属的账号；未参与排行或档案仅自己可见的用户不出现。
    </p>
  </section>
</template>
