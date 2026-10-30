<script setup lang="ts">
import { computed } from 'vue'
import { difficultyInk, difficultyStyle, withAlpha } from '@/utils/difficulty'

const props = defineProps<{
  platform: string | null | undefined
  value?: number | null
  label?: string | null
}>()

const style = computed(() => difficultyStyle(props.platform, props.value, props.label))
// 文字色在难度色的色相上调深 / 调浅到看得清，两种主题各一份，CSS 按主题选
const ink = computed(() => difficultyInk(style.value.color))
</script>

<template>
  <span
    class="diff-tag"
    :class="{ 'is-unknown': style.unknown }"
    :style="{
      '--diff-ink': ink.light,
      '--diff-ink-dark': ink.dark,
      background: withAlpha(style.color, 0.13),
      borderColor: withAlpha(style.color, 0.38)
    }"
    :title="style.title"
  >
    {{ style.text }}
  </span>
</template>

<style scoped>
.diff-tag {
  display: inline-flex;
  align-items: center;
  padding: 1px 8px;
  border: 1px solid;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 620;
  line-height: 1.55;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
  color: var(--diff-ink);
  cursor: help;
}

html[data-theme='dark'] .diff-tag {
  color: var(--diff-ink-dark);
}

/* 未评级不该抢眼：用常规字重压低存在感（不降透明度，字还要看得清） */
.diff-tag.is-unknown {
  font-weight: 500;
}
</style>
