<script setup lang="ts">
import { computed, ref } from 'vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import FilterSheet from './FilterSheet.vue'
import { optionOf, type FilterField, type FilterValues } from './types'

/**
 * 筛选条：电脑上一行筛选片，点开是下拉菜单，选了就生效；和默认值不同的筛选片用靛蓝浅底。
 * 手机上前面多一个「筛选」按钮，筛选片横向滚动，点哪个都打开底部弹层（FilterSheet）一起改。
 * 放进 grid / flex 里时外层要能收窄（grid-cols-1、min-w-0），否则一行不换行的筛选片会把外层撑宽、整页横向滚动。
 * 默认插槽放在筛选片后面、同一行里，用来加选项以外的筛选（如日期）；筛选片的样式 filter-chip 是全局的，插槽里也能用。
 */
const props = defineProps<{
  fields: FilterField[]
  modelValue: FilterValues
  /** 手机弹层的标题，如「筛选排行」 */
  sheetTitle?: string
  /** 手机弹层底部按钮的文字，如「查看结果 · 60 人」 */
  applyText?: string
}>()

const emit = defineEmits<{ 'update:modelValue': [values: FilterValues] }>()

const { isMobile } = useBreakpoint()
const sheetOpen = ref(false)

const changed = computed(() => props.fields.filter((field) => props.modelValue[field.key] !== field.defaultValue).length)

function chipText(field: FilterField) {
  const option = optionOf(field, props.modelValue[field.key])
  return field.chipText ? field.chipText(option) : option.label
}

function select(field: FilterField, index: number) {
  emit('update:modelValue', { ...props.modelValue, [field.key]: field.options[index].value })
}
</script>

<template>
  <div class="filter-bar flex items-center gap-2" :class="isMobile ? 'is-scroll' : 'flex-wrap'">
    <button
      v-if="isMobile"
      type="button"
      class="filter-chip"
      :class="{ 'is-active': changed > 0 }"
      @click="sheetOpen = true"
    >
      <span class="i-lucide-sliders-horizontal text-[15px]" aria-hidden="true" />
      筛选<span v-if="changed" class="num">&nbsp;{{ changed }}</span>
    </button>
    <template v-for="field in fields" :key="field.key">
      <button
        v-if="isMobile"
        type="button"
        class="filter-chip"
        :class="{ 'is-active': modelValue[field.key] !== field.defaultValue }"
        @click="sheetOpen = true"
      >
        {{ chipText(field) }}
        <span class="i-lucide-chevron-down text-[15px] opacity-70" aria-hidden="true" />
      </button>
      <el-dropdown v-else trigger="click" @command="(index: number) => select(field, index)">
        <button
          type="button"
          class="filter-chip"
          :class="{ 'is-active': modelValue[field.key] !== field.defaultValue }"
          :aria-label="`${field.label}：${chipText(field)}`"
        >
          {{ chipText(field) }}
          <span class="i-lucide-chevron-down text-[15px] opacity-70" aria-hidden="true" />
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item
              v-for="(option, index) in field.options"
              :key="String(option.value)"
              :command="index"
              :class="{ 'is-selected': option.value === modelValue[field.key] }"
            >
              {{ option.label }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </template>
    <slot />
    <FilterSheet
      v-if="isMobile"
      v-model="sheetOpen"
      :fields="fields"
      :values="modelValue"
      :title="sheetTitle"
      :apply-text="applyText"
      @apply="emit('update:modelValue', $event)"
    />
  </div>
</template>

<style scoped>
.filter-bar.is-scroll {
  /* 手机上横向滚动，贴到页面左右边缘（页面左右内边距 16） */
  margin: 0 -16px;
  padding: 0 16px;
  overflow-x: auto;
  scrollbar-width: none;
}

.filter-bar.is-scroll::-webkit-scrollbar {
  display: none;
}
</style>

<style>
.el-dropdown-menu__item.is-selected {
  color: var(--brand-600);
  font-weight: 600;
}
</style>
