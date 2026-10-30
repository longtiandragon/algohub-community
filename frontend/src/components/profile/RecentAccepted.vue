<script setup lang="ts">
import type { RouteLocationRaw } from 'vue-router'
import DifficultyTag from '@/components/DifficultyTag.vue'
import PlatformLogo from '@/components/PlatformLogo.vue'
import { problemCodeBare } from '@/utils/problemLabel'
import { beijingTime, dayTime } from '@/utils/statistics'

/** 最近通过：最新 5 道（平台、题号、题目、难度、「今天 18:10」这样的时间），全部记录在「做题记录」 */
defineProps<{
  rows: {
    platform: string
    title: string | null
    url: string | null
    problemKey: string | null
    difficultyLabel: string | null
    submittedAt: string | null
    timePrecision?: string
  }[]
  /** 「全部做题记录」去哪；看别人的档案时不给，不显示这个链接 */
  allTo?: RouteLocationRaw
}>()

const safeUrl = (url: string | null) => (url && /^https?:\/\//i.test(url) ? url : undefined)
/** 题号和题名：力扣的题名里带着编号，拆开显示；前面已有平台图标，题号不带平台缩写 */
const label = (row: { platform: string; problemKey: string | null; title: string | null }) =>
  problemCodeBare({ platform: row.platform, problemKey: row.problemKey ?? '', title: row.title })
</script>

<template>
  <section class="flex flex-col gap-1 border border-line rounded-card border-solid bg-surface p-4 md:(px-[22px] py-5)" data-test="recent-accepted">
    <div class="mb-2 flex items-center justify-between gap-3">
      <h2 class="m-0 text-base text-ink font-semibold">最近通过</h2>
      <RouterLink v-if="allTo" :to="allTo" class="text-[13.5px]">全部做题记录 →</RouterLink>
    </div>
    <ul v-if="rows.length" class="m-0 list-none p-0">
      <li v-for="(row, index) in rows.slice(0, 5)" :key="`${row.platform}-${row.problemKey}-${index}`" class="row">
        <PlatformLogo :platform="row.platform" :size="20" />
        <span class="mono code text-brand-600">{{ label(row).code }}</span>
        <a v-if="safeUrl(row.url)" :href="safeUrl(row.url)" target="_blank" rel="noopener noreferrer" class="title text-ink hover:text-brand-600">{{ label(row).name || row.problemKey }}</a>
        <span v-else class="title text-ink">{{ label(row).name || row.problemKey }}</span>
        <!-- 难度标签自己带 display，响应式的显隐包在外层 -->
        <span v-if="row.difficultyLabel" class="hidden shrink-0 md:inline-flex">
          <DifficultyTag :platform="row.platform" :label="row.difficultyLabel.replace('AtCoder Problems 估计', 'AtCoder 估计')" />
        </span>
        <span class="time">
          <time v-if="row.submittedAt && dayTime(row.submittedAt)" :datetime="row.submittedAt" :title="beijingTime(row.submittedAt)">{{ dayTime(row.submittedAt) }}</time>
          <span v-else>时间未知</span>
        </span>
      </li>
    </ul>
    <p v-else class="m-0 text-[13px] text-muted">还没有已同步的通过记录。</p>
  </section>
</template>

<style scoped>
.row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 44px;
  border-top: 1px solid var(--border-soft);
}

.code {
  flex-shrink: 0;
  width: 76px;
  overflow: hidden;
  font-size: 12.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 13.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.time {
  flex-shrink: 0;
  min-width: 64px;
  white-space: nowrap;
  color: var(--text-muted);
  font-size: 12px;
  text-align: right;
}
</style>
