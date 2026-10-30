<script setup lang="ts">
import { computed } from 'vue'
import DifficultyTag from '@/components/DifficultyTag.vue'
import PlatformLogo from '@/components/PlatformLogo.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import { platformName } from '@/utils/platformBrand'
import { problemCodeBare } from '@/utils/problemLabel'
import { BOOKMARK_STATUS, beijingDate, httpUrl, type BookmarkRow } from './types'

/**
 * 一条收藏：平台图标、题号题名（链到原题）；自己标的状态、难度、平台上通过没有、复习日期；笔记。
 * 到复习日期的卡片描边用警示色。右边（手机上是底部）编辑、取消收藏。
 */
const props = defineProps<{ row: BookmarkRow }>()
defineEmits<{ edit: []; remove: [] }>()

const label = computed(() => problemCodeBare(props.row))
const url = computed(() => httpUrl(props.row.url))
const status = computed(() => BOOKMARK_STATUS[props.row.status])
const md = (value: string) => {
  const d = beijingDate(value)
  return `${d.m}/${d.d}`
}
/** 复习日期存的是日期（YYYY-MM-DD），不带时区 */
const remind = computed(() => {
  const v = props.row.remindOn
  if (!v) return null
  const [, m, d] = v.slice(0, 10).split('-').map(Number)
  // 到期的（当天或更早）写「该复习了」
  return props.row.due ? `该复习了（${m}/${d}）` : `复习日期 ${m}/${d}`
})
</script>

<template>
  <article class="bm-card" :class="{ 'is-due': row.due }" :data-bookmark="row.problemId">
    <PlatformLogo :platform="row.platform" :size="26" :title="platformName(row.platform)" class="mt-0.5 shrink-0" />
    <div class="min-w-0 flex flex-1 flex-col gap-2">
      <component :is="url ? 'a' : 'span'" v-bind="url ? { href: url, target: '_blank', rel: 'noopener noreferrer' } : {}" class="bm-title text-[15.5px] text-ink font-semibold leading-[1.45] [overflow-wrap:anywhere]">
        <span v-if="label.code" class="mr-1.5 text-[13.5px] text-brand-600 font-mono">{{ label.code }}</span>{{ label.name || row.problemKey }}
      </component>
      <div class="flex flex-wrap items-center gap-2">
        <StatusBadge variant="label" :tone="status.tone">{{ status.label }}</StatusBadge>
        <DifficultyTag :platform="row.platform" :value="row.difficultyValue" :label="row.difficultyLabel" />
        <span class="text-[12.5px]" :class="row.accepted ? 'text-success-ink' : 'text-muted'">
          <template v-if="row.accepted">✓ {{ row.acAt ? `${md(row.acAt)} ` : '' }}已通过</template>
          <template v-else>○ 还没通过</template>
        </span>
        <span v-if="remind" class="text-[12.5px]" :class="row.due ? 'text-warning-ink font-semibold' : 'text-muted'">· {{ remind }}</span>
      </div>
      <p v-if="row.note" class="m-0 whitespace-pre-wrap rounded-[10px] bg-page px-3 py-2.5 text-[13.5px] text-body leading-[1.65] [overflow-wrap:anywhere]">{{ row.note }}</p>
    </div>
    <div class="bm-actions flex shrink-0 gap-1">
      <button type="button" class="bm-btn text-brand-600" :aria-label="`编辑「${label.code} ${label.name}」的收藏`" @click="$emit('edit')">编辑</button>
      <button type="button" class="bm-btn text-muted" :aria-label="`取消收藏「${label.code} ${label.name}」`" @click="$emit('remove')">取消收藏</button>
    </div>
  </article>
</template>

<style scoped>
.bm-card {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 12px 14px;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: 14px;
  background: var(--bg-elevated);
}

.bm-card.is-due {
  border-color: var(--warning-line);
}

.bm-title {
  text-decoration: none;
}

a.bm-title:hover {
  color: var(--brand-600);
}

/* 手机上按钮换到卡片底部，靠右 */
.bm-actions {
  width: 100%;
  justify-content: flex-end;
  margin-top: -4px;
}

.bm-btn {
  height: 32px;
  padding: 0 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.bm-btn:hover {
  background: var(--surface-hover);
}

.bm-btn:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}

@media (min-width: 768px) {
  .bm-card {
    flex-wrap: nowrap;
    padding: 16px 18px;
  }

  .bm-actions {
    width: auto;
    margin-top: 0;
  }
}
</style>
