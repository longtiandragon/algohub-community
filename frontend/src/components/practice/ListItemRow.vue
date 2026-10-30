<script setup lang="ts">
import { computed } from 'vue'
import BookmarkButton from '@/components/BookmarkButton.vue'
import DifficultyTag from '@/components/DifficultyTag.vue'
import { problemCodeBare } from '@/utils/problemLabel'
import { formatSolvers, httpUrl, type ListItem } from './types'

type Mark = 'LATER' | 'REVIEW' | 'DONE' | null

/**
 * 题单里的一题：序号、通过状态（圆点里打勾，读屏另写文字）、题号 + 题名（链到原题）；
 * 第二行难度、前两个标签、平台通过人数；右边标记此题（收藏到「我的收藏」），管理员多一个移出。
 */
const props = defineProps<{ item: ListItem; removable: boolean }>()
const mark = defineModel<Mark>('mark', { default: null })
defineEmits<{ remove: [] }>()

const label = computed(() => problemCodeBare(props.item))
const url = computed(() => httpUrl(props.item.url))
const tags = computed(() => props.item.tags.slice(0, 2).map((t) => t.tagRaw))
</script>

<template>
  <li class="item-row flex items-center gap-3 border-b border-line-soft border-b-solid px-4 py-2.5 md:(gap-3.5 px-5)" :data-item="item.problemId">
    <span class="num hidden w-6 shrink-0 text-right text-[13px] text-muted md:inline">{{ item.position }}</span>
    <span
      class="size-[22px] flex shrink-0 items-center justify-center rounded-full"
      :class="item.solvedByMe ? 'bg-success text-white' : 'border-[1.5px] border-line-strong border-solid'"
      :title="item.solvedByMe ? '已通过' : '还没通过'"
    >
      <span v-if="item.solvedByMe" class="i-lucide-check text-[13px]" aria-hidden="true" />
      <span class="sr-only">{{ item.solvedByMe ? '已通过' : '还没通过' }}</span>
    </span>
    <div class="min-w-0 flex flex-1 flex-col gap-1">
      <div class="min-w-0 flex items-baseline gap-2">
        <span v-if="label.code" class="shrink-0 text-[13px] text-brand-600 font-mono">{{ label.code }}</span>
        <a v-if="url" :href="url" target="_blank" rel="noopener noreferrer" class="item-title min-w-0 truncate text-[14.5px] text-ink hover:text-brand-600">{{ label.name || item.problemKey }}</a>
        <span v-else class="item-title min-w-0 truncate text-[14.5px] text-ink">{{ label.name || item.problemKey }}</span>
      </div>
      <div class="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted">
        <DifficultyTag :platform="item.platform" :value="item.difficultyValue" :label="item.difficultyLabel" />
        <span v-if="tags.length" class="hidden min-w-0 truncate md:inline">{{ tags.join(' · ') }}</span>
        <span v-if="item.solvedCount !== null" class="num" title="平台公开的通过人数">{{ formatSolvers(item.solvedCount) }} 人通过</span>
      </div>
      <p v-if="item.note" class="m-0 text-[12.5px] text-body leading-5 [overflow-wrap:anywhere]">{{ item.note }}</p>
    </div>
    <BookmarkButton v-model="mark" :problem-id="item.problemId" :accepted="item.solvedByMe" empty-label="标记" class="shrink-0" />
    <button v-if="removable" type="button" class="remove-btn" :aria-label="`把 ${label.code} ${label.name} 移出题单`" @click="$emit('remove')">
      <span class="i-lucide-x text-base" aria-hidden="true" />
    </button>
  </li>
</template>

<style scoped>
.item-row:last-child {
  border-bottom: 0;
}

.remove-btn {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
}

.remove-btn:hover {
  background: var(--danger-soft);
  color: var(--danger-ink);
}

.remove-btn:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}
</style>
