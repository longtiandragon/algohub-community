<script setup lang="ts" generic="T extends string | number">
import { computed, ref } from 'vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import BottomSheet from './BottomSheet.vue'

/**
 * 单选的筛选片，如校内动态的「全部平台 ▾」：电脑上点开是下拉菜单，手机上是底部弹层里的一列选项，选了就生效。
 * 和默认值不同时高亮（filter-chip 的 is-active）。要一次改好几个条件用 FilterBar。
 */
const props = defineProps<{
  modelValue: T
  options: { label: string; value: T }[]
  /** 这一项叫什么，如「平台」：读屏文字「平台：全部平台」，手机弹层标题「按平台看」 */
  label: string
  defaultValue: T
}>()

const emit = defineEmits<{ 'update:modelValue': [value: T] }>()

const { isMobile } = useBreakpoint()
const open = ref(false)
const current = computed(() => props.options.find((o) => o.value === props.modelValue) ?? props.options[0])

function choose(value: T) {
  emit('update:modelValue', value)
  open.value = false
}
</script>

<template>
  <el-dropdown v-if="!isMobile" trigger="click" @command="(index: number) => choose(options[index].value)">
    <button type="button" class="filter-chip" :class="{ 'is-active': modelValue !== defaultValue }" :aria-label="`${label}：${current?.label}`">
      {{ current?.label }}
      <span class="i-lucide-chevron-down text-[15px] opacity-70" aria-hidden="true" />
    </button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item v-for="(option, index) in options" :key="String(option.value)" :command="index" :class="{ 'is-selected': option.value === modelValue }">
          {{ option.label }}
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
  <template v-else>
    <button type="button" class="filter-chip" :class="{ 'is-active': modelValue !== defaultValue }" :aria-label="`${label}：${current?.label}`" @click="open = true">
      {{ current?.label }}
      <span class="i-lucide-chevron-down text-[15px] opacity-70" aria-hidden="true" />
    </button>
    <BottomSheet v-model="open" :title="`按${label}看`">
      <div role="radiogroup" :aria-label="label" class="flex flex-col pb-2">
        <button
          v-for="option in options"
          :key="String(option.value)"
          type="button"
          role="radio"
          :aria-checked="option.value === modelValue"
          class="option"
          :class="{ 'is-selected': option.value === modelValue }"
          @click="choose(option.value)"
        >
          {{ option.label }}
          <span v-if="option.value === modelValue" class="i-lucide-check text-lg" aria-hidden="true" />
        </button>
      </div>
    </BottomSheet>
  </template>
</template>

<style scoped>
.option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 48px;
  padding: 0 4px;
  border: 0;
  border-bottom: 1px solid var(--border-soft);
  background: none;
  color: var(--text-strong);
  font: inherit;
  font-size: 15px;
  text-align: left;
  cursor: pointer;
}

.option.is-selected {
  color: var(--brand-600);
  font-weight: 600;
}

.option:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: -2px;
}
</style>
