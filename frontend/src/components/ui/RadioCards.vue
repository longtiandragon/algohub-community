<script setup lang="ts" generic="T extends string | number">
import { useId } from 'vue'

/**
 * 单选卡片：几张卡片里选一张（如「谁能看到我的档案」、外观的深浅色）。每张有图标、名称和一行说明，选中的描品牌色的边。
 * 用原生单选框实现：Tab 进入、方向键切换，读屏按单选组朗读。columns 是电脑上一行几张，手机上总是一张一行。
 */
withDefaults(
  defineProps<{
    modelValue: T
    options: { value: T; label: string; description?: string; icon?: string }[]
    /** 这组选项的读屏名称 */
    label: string
    columns?: 1 | 2 | 3
  }>(),
  { columns: 3 }
)
const emit = defineEmits<{ 'update:modelValue': [value: T] }>()
const name = useId()
</script>

<template>
  <div role="radiogroup" :aria-label="label" class="grid grid-cols-1 gap-2.5" :class="{ 'md:grid-cols-2': columns === 2, 'md:grid-cols-3': columns === 3 }">
    <label v-for="option in options" :key="String(option.value)" class="radio-card" :class="{ 'is-active': option.value === modelValue }">
      <input
        class="sr-only"
        type="radio"
        :name="name"
        :value="option.value"
        :checked="option.value === modelValue"
        @change="emit('update:modelValue', option.value)"
      >
      <span class="flex items-center gap-2">
        <span v-if="option.icon" :class="option.icon" class="shrink-0 text-lg" aria-hidden="true" />
        <span class="flex-1 text-sm font-semibold">{{ option.label }}</span>
        <span class="check" aria-hidden="true"><span class="i-lucide-check text-xs" /></span>
      </span>
      <span v-if="option.description" class="text-[12.5px] text-muted leading-relaxed">{{ option.description }}</span>
    </label>
  </div>
</template>

<style scoped>
.radio-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--bg-elevated);
  color: var(--text-strong);
  cursor: pointer;
}

.radio-card:hover {
  border-color: var(--border-strong);
}

.radio-card.is-active {
  border-color: var(--brand-500);
  box-shadow: inset 0 0 0 1px var(--brand-500);
  background: color-mix(in srgb, var(--brand-soft) 45%, var(--bg-elevated));
}

.radio-card:has(input:focus-visible) {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}

.check {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  box-sizing: border-box;
  border: 1.5px solid var(--border-strong);
  border-radius: 50%;
  color: transparent;
}

.radio-card.is-active .check {
  border-color: var(--brand-500);
  background: var(--brand-500);
  color: #fff;
}
</style>
