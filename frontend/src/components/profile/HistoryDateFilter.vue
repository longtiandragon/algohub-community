<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import BottomSheet from '@/components/ui/BottomSheet.vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { campusDate, shiftDate } from '@/utils/statistics'

/**
 * 做题记录的日期筛选：一个筛选片，没选时写「日期」，选了写「9月28日」或「9/1 – 9/28」（不是今年的带年份）。
 * 点开有今天、最近 7 天、最近 30 天、本月四个快捷项，和开始、结束两个日期框（用系统自带的日期选择）；
 * 电脑上是弹出框，手机上是底部弹层。值是 [] 或 [开始, 结束]（北京时间的日期，含两端）。
 */
const props = defineProps<{ modelValue: string[] }>()
const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>()

const { isMobile } = useBreakpoint()
const open = ref(false)
const from = ref('')
const to = ref('')
const today = campusDate()

watch(open, (value) => {
  if (!value) return
  from.value = props.modelValue[0] ?? ''
  to.value = props.modelValue[1] ?? ''
})

const PRESETS: { label: string; range: () => string[] }[] = [
  { label: '今天', range: () => [today, today] },
  { label: '最近 7 天', range: () => [shiftDate(today, -6), today] },
  { label: '最近 30 天', range: () => [shiftDate(today, -29), today] },
  { label: '本月', range: () => [`${today.slice(0, 7)}-01`, today] }
]

const chipText = computed(() => {
  const [start, end] = props.modelValue
  if (!start || !end) return '日期'
  const withYear = start.slice(0, 4) !== today.slice(0, 4) || end.slice(0, 4) !== today.slice(0, 4)
  const parts = (date: string) => date.split('-').map(Number)
  if (start === end) {
    const [y, m, d] = parts(start)
    return withYear ? `${y}年${m}月${d}日` : `${m}月${d}日`
  }
  const short = (date: string) => {
    const [y, m, d] = parts(date)
    return withYear ? `${y}/${m}/${d}` : `${m}/${d}`
  }
  return `${short(start)} – ${short(end)}`
})

function apply(range: string[]) {
  emit('update:modelValue', range)
  open.value = false
}

/** 只填了一头就当那一天；开始晚于结束就对调 */
function applyInputs() {
  if (!from.value && !to.value) return apply([])
  const start = from.value || to.value
  const end = to.value || from.value
  apply(start <= end ? [start, end] : [end, start])
}
</script>

<template>
  <el-popover v-if="!isMobile" v-model:visible="open" trigger="click" placement="bottom-start" :width="300" :show-arrow="false">
    <template #reference>
      <button type="button" class="filter-chip" :class="{ 'is-active': modelValue.length }" :aria-label="`日期：${chipText}`" data-test="history-date">
        <span class="i-lucide-calendar text-[15px] opacity-70" aria-hidden="true" />{{ chipText }}
        <span class="i-lucide-chevron-down text-[15px] opacity-70" aria-hidden="true" />
      </button>
    </template>
    <div class="flex flex-col gap-3" data-test="history-date-panel">
      <div class="flex flex-wrap gap-1.5">
        <button v-for="preset in PRESETS" :key="preset.label" type="button" class="preset" @click="apply(preset.range())">{{ preset.label }}</button>
      </div>
      <label class="field"><span>开始</span><input v-model="from" type="date" :max="today" class="date-input" aria-label="开始日期"></label>
      <label class="field"><span>结束</span><input v-model="to" type="date" :max="today" class="date-input" aria-label="结束日期"></label>
      <p class="m-0 text-xs text-muted leading-relaxed">设了日期会排除没有时间的记录（如洛谷题单）；清除日期就能看到。</p>
      <div class="flex justify-end gap-2">
        <el-button @click="apply([])">清除</el-button>
        <el-button type="primary" @click="applyInputs">确定</el-button>
      </div>
    </div>
  </el-popover>
  <template v-else>
    <button type="button" class="filter-chip" :class="{ 'is-active': modelValue.length }" :aria-label="`日期：${chipText}`" data-test="history-date" @click="open = true">
      {{ chipText }}
      <span class="i-lucide-chevron-down text-[15px] opacity-70" aria-hidden="true" />
    </button>
    <BottomSheet v-model="open" title="按日期看">
      <div class="flex flex-col gap-4" data-test="history-date-panel">
        <div class="flex flex-wrap gap-2">
          <button v-for="preset in PRESETS" :key="preset.label" type="button" class="preset" @click="apply(preset.range())">{{ preset.label }}</button>
        </div>
        <label class="field"><span>开始</span><input v-model="from" type="date" :max="today" class="date-input" aria-label="开始日期"></label>
        <label class="field"><span>结束</span><input v-model="to" type="date" :max="today" class="date-input" aria-label="结束日期"></label>
        <p class="m-0 text-[13px] text-muted leading-relaxed">设了日期会排除没有时间的记录（如洛谷题单）；清除日期就能看到。</p>
      </div>
      <template #footer>
        <div class="flex gap-3">
          <el-button size="large" class="flex-1" @click="apply([])">清除</el-button>
          <el-button size="large" type="primary" class="flex-1 !ml-0" @click="applyInputs">确定</el-button>
        </div>
      </template>
    </BottomSheet>
  </template>
</template>

<style scoped>
.preset {
  height: 30px;
  padding: 0 11px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--bg-elevated);
  color: var(--text);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.preset:hover {
  border-color: var(--brand-500);
  color: var(--brand-600);
}

.preset:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 1px;
}

.field {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--text-muted);
  font-size: 13px;
}

.date-input {
  flex: 1;
  min-width: 0;
  height: 36px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--bg-elevated);
  color: var(--text-strong);
  font: inherit;
  font-size: 14px;
}

.date-input:focus-visible {
  border-color: var(--brand-500);
  outline: 2px solid color-mix(in srgb, var(--brand-500) 30%, transparent);
  outline-offset: 0;
}

@media (max-width: 767px) {
  .preset {
    height: 36px;
    padding: 0 14px;
    font-size: 14px;
  }

  .date-input {
    height: 44px;
    font-size: 16px;
  }
}
</style>
