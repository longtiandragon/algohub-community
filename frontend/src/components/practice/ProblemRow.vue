<script setup lang="ts">
import { computed } from 'vue'
import BookmarkButton from '@/components/BookmarkButton.vue'
import DifficultyTag from '@/components/DifficultyTag.vue'
import PlatformLogo from '@/components/PlatformLogo.vue'
import { platformName } from '@/utils/platformBrand'
import { categoryName, problemCodeBare } from '@/utils/problemLabel'
import { formatSolvers, httpUrl } from './types'

type Mark = 'LATER' | 'REVIEW' | 'DONE' | null

export interface ProblemsetRow {
  id: number
  platform: string
  problemKey: string
  title: string | null
  url: string | null
  difficultyLabel: string | null
  difficultyValue: number | null
  difficultySource: string | null
  solvedCount: number | null
  solved: boolean
  categories: string[]
}

/**
 * 题库的一行。电脑上是表格的一行：状态、题号（前面平台图标）、题名、算法方向、难度、通过人数、收藏星；
 * 手机上两行：题号 + 题名，下面难度、方向、通过人数。
 */
const props = defineProps<{ row: ProblemsetRow }>()
const mark = defineModel<Mark>('mark', { default: null })

const label = computed(() => problemCodeBare(props.row))
const url = computed(() => httpUrl(props.row.url))
const cats = computed(() => props.row.categories.slice(0, 2).map(categoryName))
const solvers = computed(() => (props.row.solvedCount === null ? null : formatSolvers(props.row.solvedCount)))
</script>

<template>
  <li class="pset-row border-b border-line-soft border-b-solid px-4 py-2.5 md:(px-5 py-0)" :data-problem="row.id">
    <span
      class="size-[22px] flex shrink-0 items-center justify-center rounded-full"
      :class="row.solved ? 'bg-success text-white' : 'border-[1.5px] border-line-strong border-solid'"
      :title="row.solved ? '你已经通过' : '还没有通过记录'"
    >
      <span v-if="row.solved" class="i-lucide-check text-xs" aria-hidden="true" />
      <span class="sr-only">{{ row.solved ? '已通过' : '还没通过' }}</span>
    </span>
    <span class="pset-code min-w-0 flex items-center gap-1.5">
      <PlatformLogo :platform="row.platform" :size="16" :title="platformName(row.platform)" />
      <span class="truncate text-[13px] text-brand-600 font-mono">{{ label.code || row.problemKey }}</span>
    </span>
    <span class="pset-title min-w-0">
      <a v-if="url" :href="url" target="_blank" rel="noopener noreferrer" class="block truncate text-[14.5px] text-ink hover:text-brand-600">{{ label.name || '未命名题目' }}</a>
      <span v-else class="block truncate text-[14.5px] text-ink">{{ label.name || '未命名题目' }}</span>
    </span>
    <span class="pset-meta">
      <span class="pset-cats min-w-0 gap-1.5 overflow-hidden">
        <!-- 平板上这一列窄，只放第一个方向，免得第二个被切一半 -->
        <span v-for="(c, i) in cats" :key="c" class="h-[22px] shrink-0 items-center rounded-md bg-surface-2 px-[7px] text-xs text-body" :class="i ? 'hidden xl:inline-flex' : 'inline-flex'">{{ c }}</span>
      </span>
      <span class="pset-diff">
        <DifficultyTag :platform="row.platform" :value="row.difficultyValue" :label="row.difficultyLabel" />
      </span>
      <span class="pset-solvers num text-[13px] text-muted md:text-right" :title="solvers === null ? '这个平台没有公开通过人数' : '平台公开的通过人数'">
        <template v-if="solvers !== null">{{ solvers }}<span class="md:hidden"> 人通过</span></template>
        <template v-else><span class="hidden md:inline">—</span></template>
      </span>
    </span>
    <BookmarkButton v-model="mark" :problem-id="row.id" :accepted="row.solved" icon-only class="pset-star" />
  </li>
</template>

<style scoped>
/* 手机：状态 | 题号 题名 | 星；第二行难度、方向、通过人数 */
.pset-row {
  display: grid;
  grid-template-columns: 22px auto minmax(0, 1fr) 32px;
  grid-template-areas:
    'mark code title star'
    '.    meta meta  star';
  column-gap: 10px;
  row-gap: 4px;
  align-items: center;
}

.pset-row > span:first-child {
  grid-area: mark;
}

.pset-code {
  grid-area: code;
}

.pset-title {
  grid-area: title;
}

.pset-star {
  grid-area: star;
}

.pset-meta {
  display: flex;
  grid-area: meta;
  align-items: center;
  gap: 10px;
}

.pset-cats {
  display: none;
}

.pset-row:last-child {
  border-bottom: 0;
}

@media (min-width: 768px) {
  .pset-row {
    grid-template-columns: 22px 96px minmax(0, 2fr) minmax(0, 1.4fr) 64px 80px 32px;
    grid-template-areas: none;
    column-gap: 12px;
    height: 52px;
  }

  .pset-row > * {
    grid-area: auto !important;
  }

  /* 电脑上方向、难度、通过人数各占一列 */
  .pset-meta {
    display: contents;
  }

  .pset-cats {
    display: flex;
  }
}
</style>
