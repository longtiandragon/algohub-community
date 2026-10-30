<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { get, notifyError } from '@/api/client'
import BookmarkButton from '@/components/BookmarkButton.vue'
import DifficultyTag from '@/components/DifficultyTag.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import { problemCodeBare } from '@/utils/problemLabel'
import { dayTime } from '@/utils/statistics'
import { formatSolvers, httpUrl } from './types'

type Mark = 'LATER' | 'REVIEW' | 'DONE' | null

interface ProblemRow {
  id: number
  problemKey: string
  title: string | null
  url: string | null
  difficultyLabel: string | null
  difficultyValue: number | null
  acAt?: string | null
  solvedCount?: number | null
}

interface CategoryDetail {
  platform: string
  category: string
  solved: ProblemRow[]
  todo: ProblemRow[]
  todoBasis: string
  difficultyHistogram: { difficultyValue: number; count: number }[]
  note: string
}

/**
 * 一个算法方向的详情：我通过的题（最近 20 道）、这些题的难度分布、题库里还没通过的题。
 * 只是内容；外面由页面包一层（电脑上右侧抽屉，手机上整屏底部弹层）。
 */
const props = defineProps<{ platform: string; category: string }>()

const detail = ref<CategoryDetail | null>(null)
const loading = ref(false)
const marks = ref<Record<number, Mark>>({})

let requestId = 0
watch(
  () => [props.platform, props.category] as const,
  async ([platform, category]) => {
    if (!category) return
    const id = ++requestId
    loading.value = true
    detail.value = null
    try {
      const data = await get<CategoryDetail>('/skill-map/category', { platform, category, limit: 20 })
      if (id !== requestId) return
      detail.value = data
      await loadMarks(id)
    } catch (e) {
      if (id === requestId) notifyError(e)
    } finally {
      if (id === requestId) loading.value = false
    }
  },
  { immediate: true }
)

/** 一次取回这批题的收藏状态；取不到就当作都没标记 */
async function loadMarks(id: number) {
  const ids = [...(detail.value?.solved ?? []), ...(detail.value?.todo ?? [])].map((p) => p.id)
  if (!ids.length) return
  try {
    const found = await get<Record<string, Exclude<Mark, null>>>('/bookmarks/status', { problemIds: ids.join(',') })
    if (id === requestId) marks.value = Object.fromEntries(ids.map((i) => [i, found[String(i)] ?? null]))
  } catch {
    marks.value = {}
  }
}

const histMax = computed(() => Math.max(1, ...(detail.value?.difficultyHistogram ?? []).map((h) => h.count)))
const label = (row: ProblemRow) => problemCodeBare({ platform: props.platform, problemKey: row.problemKey, title: row.title })
</script>

<template>
  <div class="flex flex-col gap-6" data-test="category-detail">
    <div v-if="loading && !detail" class="flex flex-col gap-3">
      <Skeleton v-for="i in 6" :key="i" class="h-9 w-full" />
    </div>
    <template v-else-if="detail">
      <section class="flex flex-col gap-2" aria-labelledby="cat-solved">
        <h3 id="cat-solved" class="m-0 text-sm text-ink font-semibold">我通过的题<span class="ml-1 text-xs text-muted font-normal">最近 {{ detail.solved.length }} 道</span></h3>
        <p v-if="!detail.solved.length" class="m-0 text-[13px] text-muted">这个方向还没有通过记录。</p>
        <ul v-else class="m-0 list-none border border-line-soft rounded-control border-solid p-0">
          <li v-for="item in detail.solved" :key="item.id" class="detail-row">
            <span class="shrink-0 text-[13px] text-brand-600 font-mono">{{ label(item).code || item.problemKey }}</span>
            <a v-if="httpUrl(item.url)" :href="httpUrl(item.url)" target="_blank" rel="noopener noreferrer" class="min-w-0 flex-1 truncate text-sm text-ink hover:text-brand-600">{{ label(item).name }}</a>
            <span v-else class="min-w-0 flex-1 truncate text-sm text-ink">{{ label(item).name }}</span>
            <DifficultyTag :platform="platform" :value="item.difficultyValue" :label="item.difficultyLabel" />
            <span v-if="item.acAt" class="hidden shrink-0 text-xs text-muted md:inline">{{ dayTime(item.acAt) }}</span>
            <BookmarkButton v-model="marks[item.id]" :problem-id="item.id" accepted icon-only />
          </li>
        </ul>
      </section>

      <section v-if="detail.difficultyHistogram.length" class="flex flex-col gap-2" aria-labelledby="cat-hist">
        <h3 id="cat-hist" class="m-0 text-sm text-ink font-semibold">通过题的难度分布</h3>
        <ol class="hist m-0 flex list-none items-end gap-1.5 p-0" aria-label="每个难度通过几题">
          <li v-for="h in detail.difficultyHistogram" :key="h.difficultyValue" class="min-w-0 flex flex-1 flex-col items-center gap-1">
            <span class="num text-[11px] text-muted">{{ h.count }}</span>
            <span class="w-full max-w-7 rounded-t bg-success" :style="{ height: `${Math.max(4, (h.count / histMax) * 96)}px` }" aria-hidden="true" />
            <span class="num text-[11px] text-muted">{{ h.difficultyValue }}</span>
            <span class="sr-only">难度 {{ h.difficultyValue }} 通过 {{ h.count }} 题</span>
          </li>
        </ol>
        <p class="m-0 text-xs text-muted">横轴是这个平台的原始难度，只在同一平台内比较。</p>
      </section>

      <section class="flex flex-col gap-2" aria-labelledby="cat-todo">
        <h3 id="cat-todo" class="m-0 text-sm text-ink font-semibold">还没通过的题</h3>
        <p class="m-0 text-xs text-muted leading-[1.6]">{{ detail.todoBasis }}</p>
        <p v-if="!detail.todo.length" class="m-0 text-[13px] text-muted">题库里这个方向没有符合条件的没通过的题。</p>
        <ul v-else class="m-0 list-none border border-line-soft rounded-control border-solid p-0">
          <li v-for="item in detail.todo" :key="item.id" class="detail-row">
            <span class="shrink-0 text-[13px] text-brand-600 font-mono">{{ label(item).code || item.problemKey }}</span>
            <a v-if="httpUrl(item.url)" :href="httpUrl(item.url)" target="_blank" rel="noopener noreferrer" class="min-w-0 flex-1 truncate text-sm text-ink hover:text-brand-600">{{ label(item).name }}</a>
            <span v-else class="min-w-0 flex-1 truncate text-sm text-ink">{{ label(item).name }}</span>
            <DifficultyTag :platform="platform" :value="item.difficultyValue" :label="item.difficultyLabel" />
            <span v-if="item.solvedCount" class="hidden shrink-0 text-xs text-muted md:inline">{{ formatSolvers(item.solvedCount) }} 人通过</span>
            <BookmarkButton v-model="marks[item.id]" :problem-id="item.id" :accepted="false" icon-only />
          </li>
        </ul>
      </section>

      <p class="m-0 text-xs text-muted leading-[1.6]">{{ detail.note }}</p>
    </template>
  </div>
</template>

<style scoped>
.detail-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 44px;
  padding: 4px 8px 4px 12px;
  border-bottom: 1px solid var(--border-soft);
}

.detail-row:last-child {
  border-bottom: 0;
}

.hist {
  min-height: 140px;
  padding: 8px 4px 0;
}
</style>
