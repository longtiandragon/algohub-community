<script setup lang="ts">
import { reactive, watch } from 'vue'
import BottomSheet from './BottomSheet.vue'
import StatusBadge from './StatusBadge.vue'
import type { FilterField, FilterValues } from './types'

/**
 * 手机上的筛选弹层：每个条件一组，按 display 用胶囊、带解释的卡片或下拉来选。
 * 在弹层里改的先放草稿，点「查看结果」才生效；直接关掉等于放弃，「恢复默认」回到各条件的默认值。
 */
const props = defineProps<{
  modelValue: boolean
  fields: FilterField[]
  values: FilterValues
  title?: string
  /** 底部主按钮的文字，如「查看结果 · 60 人」 */
  applyText?: string
}>()

const emit = defineEmits<{ 'update:modelValue': [value: boolean]; apply: [values: FilterValues] }>()

const draft = reactive<FilterValues>({})

watch(
  () => props.modelValue,
  (open) => {
    if (open) Object.assign(draft, props.values)
  },
  { immediate: true }
)

function reset() {
  for (const field of props.fields) draft[field.key] = field.defaultValue
}

function apply() {
  emit('apply', { ...draft })
  emit('update:modelValue', false)
}

const placeholderOf = (field: FilterField) =>
  field.options.find((option) => option.value === field.defaultValue)?.label ?? field.label
</script>

<template>
  <BottomSheet
    :model-value="modelValue"
    :title="title ?? '筛选'"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template #header-extra>
      <button type="button" class="h-11 border-0 bg-transparent px-1.5 text-[14.5px] text-brand" @click="reset">
        恢复默认
      </button>
    </template>
    <div class="flex flex-col gap-5">
      <fieldset v-for="field in fields" :key="field.key" class="m-0 min-w-0 border-0 p-0">
        <legend class="mb-2 p-0 text-[13px] font-semibold text-body">{{ field.label }}</legend>
        <div v-if="field.display === 'cards'" class="flex flex-col gap-2">
          <label
            v-for="option in field.options"
            :key="String(option.value)"
            class="sheet-card"
            :class="{ 'is-active': draft[field.key] === option.value }"
          >
            <input
              type="radio"
              class="sr-only"
              :name="`sheet-${field.key}`"
              :checked="draft[field.key] === option.value"
              @change="draft[field.key] = option.value"
            />
            <span class="flex min-w-0 flex-1 flex-col gap-px">
              <span class="flex items-center gap-1.5 text-[14.5px] text-ink">
                {{ option.label }}
                <StatusBadge v-if="option.badge" variant="label" tone="success">{{ option.badge }}</StatusBadge>
              </span>
              <span v-if="option.description" class="text-xs leading-[18px] text-muted">{{ option.description }}</span>
            </span>
            <span class="sheet-radio" aria-hidden="true" />
          </label>
        </div>
        <el-select
          v-else-if="field.display === 'select'"
          v-model="draft[field.key]"
          size="large"
          class="w-full"
          clearable
          :value-on-clear="null"
          :placeholder="placeholderOf(field)"
          :aria-label="field.label"
        >
          <el-option
            v-for="option in field.options.filter((item) => item.value !== null)"
            :key="String(option.value)"
            :label="option.label"
            :value="option.value as string | number"
          />
        </el-select>
        <div v-else class="flex flex-wrap gap-2">
          <label
            v-for="option in field.options"
            :key="String(option.value)"
            class="sheet-pill"
            :class="{ 'is-active': draft[field.key] === option.value }"
          >
            <input
              type="radio"
              class="sr-only"
              :name="`sheet-${field.key}`"
              :checked="draft[field.key] === option.value"
              @change="draft[field.key] = option.value"
            />
            {{ option.label }}
          </label>
        </div>
      </fieldset>
    </div>
    <template #footer>
      <el-button type="primary" class="sheet-apply" @click="apply">{{ applyText ?? '查看结果' }}</el-button>
    </template>
  </BottomSheet>
</template>

<style scoped>
.sheet-pill {
  display: inline-flex;
  align-items: center;
  height: 36px;
  padding: 0 14px;
  border: 1px solid var(--border-strong);
  border-radius: 999px;
  background: var(--bg-elevated);
  color: var(--text);
  font-size: 14px;
  cursor: pointer;
}

.sheet-pill.is-active {
  border-color: var(--brand-400);
  background: var(--brand-soft);
  color: var(--brand-600);
  font-weight: 600;
}

.sheet-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--bg-elevated);
  cursor: pointer;
}

.sheet-card.is-active {
  border-color: var(--brand-400);
  box-shadow: inset 0 0 0 0.5px var(--brand-400);
  background: color-mix(in srgb, var(--brand-soft) 45%, var(--bg-elevated));
}

.sheet-radio {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  border: 1.5px solid var(--border-strong);
  border-radius: 50%;
}

.is-active .sheet-radio {
  border: 5px solid var(--brand-500);
}

.sheet-pill:has(input:focus-visible),
.sheet-card:has(input:focus-visible) {
  outline: 2px solid var(--brand-500);
  outline-offset: 1px;
}

.sheet-apply.el-button {
  width: 100%;
  height: 50px;
  border-radius: 12px;
  font-size: 16px;
  font-weight: 600;
}
</style>
